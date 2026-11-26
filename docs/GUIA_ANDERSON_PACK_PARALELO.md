# Guía de PACK paralelo (Anderson)

## Objetivo

Eliminar físicamente los registros marcados `0x01`, conservar sin deserializar los bytes originales de cada registro activo y mantener su orden. El formato de entrada es `flag (1 byte) + longitud (int big-endian, 4 bytes) + payload`, según `ESPECIFICACION_FORMATO_BINARIO.md`.

## Arquitectura

- `DataPartition`: rango de bytes semiabierto `[inicio, fin)`.
- `DataPartitioner`: recorre secuencialmente cabeceras y longitudes y conserva solo los cortes (O(P) memoria para P particiones solicitadas), no un offset por registro. Elige el límite anterior o posterior más cercano al objetivo por bytes; un mismo límite puede satisfacer objetivos próximos, por lo que no se crean rangos vacíos. No divide, omite ni duplica registros. Archivo vacío produce cero particiones.
- `PartitionWorker`: `Callable` autónomo con sus propios `RandomAccessFile`; examina su rango, descarta flag `1` y copia flag, longitud y payload íntegros para flag `0` a un temporal individual. Comprueba interrupción entre registros y bloques de copia, cierra recursos con try-with-resources y elimina su temporal si no completa.
- `BlockMerger`: concatena temporales en orden de particiones con buffer acotado y vuelve a validar la secuencia compactada.
- `PackResult`: contadores, tamaños, duración, hilos, rutas y porcentaje/bytes ahorrados.
- `ParallelPackEngine`: prepara snapshot, ejecuta trabajadores en `ExecutorService`, espera resultados, verifica contadores, fusiona y publica el resultado en una ruta distinta.

## Seguridad e integridad

`pack(Path origen, int hilos)` escribe por defecto en `origen + ".packed"`. También se puede indicar explícitamente destino con `pack(origen, destino, hilos)`. Solo se permiten 2, 4 u 8 hilos. El archivo original jamás se reemplaza. El origen se copia a un snapshot de trabajo mientras se mantiene un bloqueo exclusivo de archivo (`FileLock`), y los trabajadores leen solo ese snapshot, así no se cruzan entre sí ni con escrituras cooperativas. Los archivos temporales se alojan en un directorio privado por ejecución y se limpian al terminar.

El bloqueo exclusivo de `FileChannel` es cooperativo: coordina únicamente con escritores que respeten el mismo mecanismo de bloqueo. No protege frente a escritores que lo ignoren. La integración robusta requiere coordinar PACK con la cola/bloqueo de mutaciones del sistema; no existe hoy esa API en `StorageEngine`.

Al terminar o fallar, el engine cancela tareas pendientes y espera la terminación real del `ExecutorService` antes de eliminar el workspace. Si recibe `InterruptedException`, hace cancelación y limpieza y restaura el estado de interrupción del hilo llamador. Ningún destino se publica antes de finalizar y validar la fusión; el origen nunca se reemplaza.

La publicación intenta primero `ATOMIC_MOVE` con reemplazo. Si el proveedor indica que los movimientos atómicos no están soportados y el destino ya existe, PACK aborta y conserva ese destino. Solo cuando no existe se permite fallback a `Files.move` sin `REPLACE_EXISTING`, de modo que una creación concurrente del destino no se sobrescribe. El origen se abre actualmente con `READ` y `WRITE` porque el bloqueo exclusivo de `FileChannel` requiere un canal escribible; cambiarlo por un bloqueo compartido debilitaría la coordinación con escritores exclusivos y el contrato compartido no define otro protocolo. Por ello, un archivo con solo permisos de lectura puede no ser compactable con esta coordinación.

Para pruebas, existen inyecciones package-private de checkpoint por registro y operación de movimiento. No amplían la API pública y permiten sincronizar la cancelación y simular `AtomicMoveNotSupportedException` sin depender del sistema de archivos local.

## Metadata e integración pendiente

`StorageEngine` solo expone operaciones CRUD y metadata; no expone pausa de escrituras ni sustitución/reapertura del archivo activo. `RafStorageEngine` y `MetadataRepository` en esta rama son esqueletos; la instancia del motor no ofrece un mecanismo para cambiar su `RandomAccessFile`. Por eso este módulo deliberadamente no modifica Metadata ni cambia el nombre del archivo activo. Tras integración coordinada, Alejandro debe detener mutaciones, ejecutar PACK, cambiar/reabrir el archivo activo y solo entonces invocar `Metadata.resetAfterCompaction(activos, últimoOffset, nombreNuevo)` y persistir metadata. Dicho método deja `indexSynced=false`, como corresponde: los índices deben reconstruirse.

El destino debe estar en un directorio ya existente y ser distinto del origen. Si el proveedor no soporta `ATOMIC_MOVE`, un destino preexistente se conserva y la operación falla; solo se usa el fallback estándar cuando el destino todavía no existe y sin opción de reemplazo.

## Ejecución

Compilar el proyecto:

```powershell
javac -d bin -sourcepath src/main/java (Get-ChildItem -Recurse -Filter *.java src/main/java).FullName
```

Uso desde Java:

```java
PackResult result = new ParallelPackEngine().pack(Path.of("data/policies_data.dat"), 4);
System.out.println(result.getOutput());
System.out.println(result.getActiveRecords() + " activos; reducción " + result.getReductionPercent() + "%");
```

Pruebas estándar sin dependencias externas:

```powershell
javac -d bin -sourcepath src/main/java; javac -d bin -cp bin src/test/java/pe/edu/unmsm/lsdfms/pack/ParallelPackEngineTest.java
java -ea -cp bin pe.edu.unmsm.lsdfms.pack.ParallelPackEngineTest
```

## Cobertura y resultados

La prueba ejecutable cubre archivo vacío, todos activos, todos eliminados, mezcla y longitudes variables (incluyendo payload vacío), 2/4/8 hilos, orden/contenido sin omisiones ni duplicados, cabecera truncada, bandera inválida, longitud negativa y longitud superior a los bytes restantes. También verifica destino preexistente con `ATOMIC_MOVE` no soportado, fallback sin destino, cancelación directa y end-to-end del engine con checkpoint determinista, terminación de workers, restauración del interrupt, error de fusión y limpieza de su archivo incompleto, conservación del origen/destino ante fallos y 100,000 registros generados y verificados en streaming. No se incluyen mediciones de rendimiento: no se han realizado benchmarks.

## Límites conocidos

- La sincronización con mutaciones del motor/cola y el cambio transaccional de Metadata/archivo activo requieren un contrato de integración de Alejandro; no se inventaron métodos ni se modificó `StorageEngine`.
- `FileLock` coordina únicamente escritores que usan el mismo bloqueo; no evita mutaciones de escritores no cooperativos.
- La recuperación ante caída abrupta del proceso puede dejar el directorio temporal; la limpieza garantizada aplica a salida normal y excepciones capturables.
