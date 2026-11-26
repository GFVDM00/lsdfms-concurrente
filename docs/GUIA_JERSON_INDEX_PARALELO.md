# Guía de INDEX paralelo (Jerson)

## Objetivo

Reconstruir los índices binarios de las columnas configuradas con un hilo por columna y responder búsquedas por clave en `O(log n)` sobre offsets físicos del `.dat`. Cumple **RF-02**, **RF-06** y **RF-07** con JDK puro (`RandomAccessFile`, `ExecutorService`), sin librerías externas.

## Arquitectura

- `ColumnKeyExtractor`: traduce el nombre de columna a tipo de clave y extrae el campo desde el payload (`id` como `long`; `dni`, `titular`, `tipoSeguro` y `departamento` como UTF-8). Valida la longitud de cada campo de texto.
- `IndexKey`: clave normalizada con comparador determinista. Los textos se comparan byte a byte como **unsigned UTF-8**, en el mismo orden en que se almacenan; los `long` se comparan numéricamente.
- `IndexScanner`: recorre el `.dat` por cabeceras (`flag` 1 byte + `recordLength` 4 bytes), valida bandera, longitud mínima de 26 bytes y truncamiento, y entrega solo payloads de registros activos (`0x00`).
- `ColumnIndexTask`: `Callable` autónomo. Recolecta `(clave, offset)`, ordena por `(clave, offset)` y escribe un `.idx` temporal propio con `RandomAccessFile`. Sin estado compartido ni archivos compartidos entre tareas.
- `ParallelIndexEngine`: toma snapshot del archivo activo, lanza una tarea por columna en `ExecutorService`, valida los resultados, publica los índices y atiende las búsquedas. Expone métricas en `IndexResult` para el módulo de benchmarking.
- `IndexFileFormat`: define y valida el formato físico de los `.idx`.

## Formato `.idx`

Un archivo por columna, hermano del `.dat`: `policies_data_id.idx`, `policies_data_dni.idx`, etc.

```text
+---------------------------------------------------------------+
| "LSDX" (4B) | version=1 (4B) | kind (4B) | lenNombre (2B)       |
| reservado (2B) | entryCount (8B) | keysOffset (8B)             | 32 B
+---------------------------------------------------------------+
| nombre de la columna (lenNombre bytes UTF-8)                  |
+---------------------------------------------------------------+
| tabla: entryCount x [ keyCode (8B) | recordOffset (8B) ]      | 16 B/entrada
+---------------------------------------------------------------+
| bloque de claves: [ short len | bytes UTF-8 ] por clave       |
+---------------------------------------------------------------+
```

- `kind=1` (`LONG`): `keyCode` es el valor `long`; no hay bloque de claves.
- `kind=2` (`TEXT`): `keyCode` es el offset absoluto de la clave dentro del bloque. Así la tabla tiene tamaño fijo y la búsqueda binaria es de acceso directo incluso con textos de longitud variable.
- `keysOffset = 32 + lenNombre + entryCount * 16`. La lectura valida magic, versión, tipo, nombre y que el tamaño del archivo coincida con la cabecera.

## Orden, duplicados y borrado lógico

- Los registros eliminados (`0x01`) **no** entran al índice.
- Las entradas se ordenan por `(clave, offset)` ascendente. Con claves repetidas, `searchOffset` devuelve el **menor offset activo**; recorre las entradas iguales hasta encontrar una cuya bandera en el `.dat` siga siendo `0x00`.
- Si la clave no existe, o todas sus coincidencias fueron borradas después de la última reindexación, devuelve `-1`.
- `id`, `dni`, `titular`, `departamento` y `tipoSeguro` son extraíbles; por defecto se indexan los cuatro configurados en metadata (`id,dni,departamento,tipoSeguro`). La lista se toma de `Metadata.getIndexedColumns()`, se deduplica y se filtra contra las columnas soportadas.

## Seguridad e integridad

- `rebuildAllIndexes(StorageEngine)` copia el `.dat` a un snapshot con buffer acotado y verifica que el tamaño no cambió durante la copia; los trabajadores leen solo ese snapshot. El `.dat` jamás se modifica.
- CADA tarea escribe su propio temporal y `ParallelIndexEngine` publica cada `.idx` con `ATOMIC_MOVE` + reemplazo. Si el proveedor no soporta movimientos atómicos y el destino ya existe, la operación aborta conservando el índice anterior; solo se permite el fallback sin `REPLACE_EXISTING` cuando el destino no existe.
- Si una tarea falla, se cancelan las restantes, se espera la terminación real del `ExecutorService` y se borra el workspace temporal. Con `InterruptedException` se restaura el estado de interrupción del hilo llamador.
- `threads = 0` (constructor por defecto) usa un hilo por columna. También se pueden forzar `1`, `2`, `4` u `8` hilos para benchmarks; `threads=1` es la línea base secuencial y las tareas se distribuyen entre los hilos sin compartir archivos.
- `rebuildAllIndexes` **no** marca `isIndexSynced`: `QueueDispatcher` lo hace tras una reconstrucción exitosa. Inserciones, borrados y PACK dejan el flag en `false`, por lo que el índice se considera desactualizado hasta el próximo `INDEX()`.

## Integración

`ServerApp` inyecta `new ParallelIndexEngine(new File("data").toPath())` mediante `server.setIndexEngine(...)`. Con eso:

- `GET /api/search?column=dni&key=...` deja de responder `503` y devuelve la póliza reconstruida desde el offset encontrado (`NativeHttpServer`).
- `POST /api/maintenance` encola `PACK` y luego `INDEX`; `QueueDispatcher` invoca `rebuildAllIndexes(storage)` y sincroniza metadata.
- `GET /api/status` continúa mostrando `isIndexSynced`, `lastIndexedAt` e `indexedColumns`.

No se modificaron los contratos `IndexEngine` ni `StorageEngine`.

## Ejecución

```bash
mkdir -p bin data
javac -d bin -sourcepath src/main/java $(find src/main/java -name "*.java")
java -cp bin pe.edu.unmsm.lsdfms.Main
```

Pruebas del módulo (JDK puro, sin frameworks):

```bash
javac -d bin -sourcepath src/main/java:src/test/java \
  $(find src/main/java src/test/java -name "*.java")
java -ea -cp bin pe.edu.unmsm.lsdfms.index.ParallelIndexEngineTest
```

## Cobertura y límites conocidos

La prueba cubre búsquedas por columna numérica y textual, registros eliminados, claves duplicadas, archivo vacío y todo eliminado, 1/2/4/8 hilos y hilo por columna, corrupción del `.dat`, corrupción del `.idx`, interrupción y limpieza, integridad del `.dat` y 100.000 registros en streaming.

- El snapshot es de solo lectura y no coordina con escritores que ignoran el protocolo de la cola; la integración robusta depende de que las mutaciones pasen por `ThreadSafeRequestQueue`, como ocurre en el servidor.
- Tras un PACK, los offsets cambian: es obligatorio ejecutar `INDEX()` antes de fiarse de los índices (`Metadata.resetAfterCompaction` ya deja `indexSynced=false`).
- La publicación es atómica por archivo, no transaccional entre columnas: una falla al publicar la cuarta columna puede dejar las tres anteriores actualizadas; la metadata seguirá marcada como desactualizada porque el `INDEX()` habrá fallado.
