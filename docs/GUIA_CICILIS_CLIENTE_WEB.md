# Módulo 5 — Aplicación Web Cliente y Modelado del Caso Real (Cicilis)

Documentación del cliente web del LSDFMS: el componente que materializa **RF-03**
(CRUD desde el navegador), consume **RF-02** (búsqueda dicotómica por índice) y
hace visible **RF-07** (ciclo de mantenimiento y estado del índice).

---

## 1. Caso real de negocio (RNF-05)

La entidad gestionada es la **póliza de seguros** (`InsurancePolicy`), definida en
[`docs/ESPECIFICACION_FORMATO_BINARIO.md`](ESPECIFICACION_FORMATO_BINARIO.md).

| Campo | Regla de negocio aplicada en el cliente |
|---|---|
| `id` | Entero positivo. Identificador de póliza, indexado ($I_1$). |
| `dni` | Exactamente 8 dígitos (`pattern="\d{8}"`), indexado ($I_2$). |
| `titular` | Texto libre de longitud variable, máximo 120 caracteres. |
| `tipoSeguro` | Lista cerrada: Salud Integral, Vehicular Todo Riesgo, Vida Ley, SOAT, Hogar Multiriesgo, Accidentes Personales. Indexado. |
| `departamento` | Los 24 departamentos del Perú más el Callao. Indexado. |
| `prima` | Decimal no negativo, se muestra formateado en soles. |
| `fechaEmision` | Fecha ISO `YYYY-MM-DD`, por defecto la fecha actual. |

La longitud variable de `titular`, `tipoSeguro` y `departamento` es lo que ejercita
el formato de registro variable de RF-01 desde la interfaz.

---

## 2. Archivos del módulo

```text
src/main/resources/public/
├── index.html   # Estructura de la página (estado, formulario, búsqueda, tabla, bitácora)
├── styles.css   # Estilos propios, sin frameworks (RNF-01)
└── app.js       # Lógica de consumo de la API, sin librerías externas (RNF-01)

src/main/java/pe/edu/unmsm/lsdfms/server/
└── DataFileBrowser.java   # Lector paginado de solo lectura para la tabla del cliente
```

El servidor nativo sirve estos archivos desde el contexto `/` y el cliente queda
disponible en `http://<host>:<puerto>/`.

---

## 3. Endpoints consumidos

| Operación en la UI | Petición HTTP | Camino en el servidor |
|---|---|---|
| Panel de estado y badge del índice | `GET /api/status` | Lectura directa de `Metadata` |
| Tabla de registros | `GET /api/policies/list?page&size&estado` | `DataFileBrowser`, solo lectura |
| Registrar póliza | `POST /api/policies` | Cola → `RequestType.INSERT` |
| Modificar póliza | `PUT /api/policies?offset=N` | Cola → `RequestType.UPDATE` |
| Eliminar (lógico) | `DELETE /api/policies?offset=N` | Cola → `RequestType.DELETE_LOGICAL` |
| Búsqueda por índice | `GET /api/search?column&key` | `IndexEngine.searchOffset`, $O(\log n)$ |
| Mantenimiento | `POST /api/maintenance` | Cola → `MAINTENANCE_PACK` + `MAINTENANCE_INDEX` |

Las tres mutaciones pasan por la **cola sincronizada** (RF-04): el cliente nunca
escribe en el archivo, solo encola y espera la respuesta del `QueueDispatcher`.
Las lecturas (listado y búsqueda) no entran a la cola, tal como exige RF-03.

### 3.1. Modificación (`PUT`)

La modificación de un registro de longitud variable no se reescribe in situ: el
despachador marca el offset anterior como borrado lógico e inserta la versión
nueva al final del archivo, devolviendo el nuevo offset. Por eso cada edición
incrementa `deletedRecords` y deja trabajo pendiente para `PACK()`.

### 3.2. Listado paginado

`StorageEngine` solo expone lectura por offset exacto, de modo que el listado se
resuelve con `DataFileBrowser`: abre su propio `RandomAccessFile` en modo `"r"`
sobre el archivo activo declarado en `Metadata` y recorre las cabeceras
`flag(1B) + length(4B)` saltando registros sin deserializarlos salvo los de la
página pedida. No escribe nunca, por lo que no compite con el despachador
(RNF-02). Parámetros: `page` (base 0), `size` (máx. 200) y
`estado` ∈ {`activos`, `eliminados`, `todos`}.

---

## 4. Demostración en vivo del ciclo de mantenimiento (RF-07)

El indicador superior refleja `isIndexSynced` de la metadata, con sondeo cada 3 s:

1. **Sincronizado** (verde) — los índices `.idx` reflejan el archivo de datos.
2. Se inserta, modifica o elimina un registro desde la interfaz.
3. El badge pasa a **Desactualizado** (ámbar, parpadeante) y la bitácora anota el
   cambio de estado. Una búsqueda del registro recién insertado devuelve "sin
   coincidencias" y la UI explica que el índice está desactualizado.
4. Se pulsa **Ejecutar mantenimiento (PACK + INDEX)**.
5. Al terminar, el badge vuelve a **Sincronizado**, se actualiza "última
   indexación" y la misma búsqueda ya devuelve el offset físico del registro.

Este es el recorrido que se demuestra en la sustentación.

---

## 5. Ejecución local

```bash
# Compilar
javac -d bin -sourcepath src/main/java $(find src/main/java -name "*.java")

# Levantar el servidor (sirve API y cliente web)
java -cp bin pe.edu.unmsm.lsdfms.server.ServerApp 8080
```

Abrir `http://localhost:8080/`.

El `StaticFileHandler` resuelve los archivos contra `src/main/resources/public`
relativo al directorio de trabajo, así que el servidor debe arrancarse desde la
raíz del repositorio.

### Apuntar a la instancia en la nube

Para servir el cliente desde una máquina y la API desde otra (pruebas remotas de
RNF-04), basta con abrir el cliente con el parámetro `api`:

```text
http://localhost:8080/?api=http://<IP-de-la-VM>:8080
```

El servidor ya responde con cabeceras CORS abiertas en todos los endpoints.

---

## 6. Integración de `PACK()` en el mantenimiento

`POST /api/maintenance` ejecuta `PACK()` e `INDEX()` reales. `ParallelPackEngine`
(Módulo 3) no implementa la interfaz `PackEngine` que espera `QueueDispatcher` y,
por diseño, nunca reemplaza su archivo de origen: escribe el compactado aparte.
El paso de publicación que faltaba lo añade `pack/StoragePackAdapter`:

1. Compacta el archivo activo hacia un hermano `.packed`.
2. `StorageEngine.replaceDataFile(...)` cierra el descriptor, reemplaza el archivo
   de forma atómica, lo reabre y reconstruye offsets y contadores desde el
   contenido nuevo, dejando los índices marcados como desactualizados.
3. Si algo falla, el archivo activo queda intacto y el temporal se descarta.

El descriptor se cierra antes de mover porque en Windows un handle abierto impide
reemplazar el destino. Solo lo invoca el hilo del `QueueDispatcher`, único escritor
del archivo, por lo que durante la compactación no hay mutaciones concurrentes
(RNF-02). Verificación extremo a extremo desde la API, con 5000 registros y 40 %
marcados:

```text
antes:   totalRecords=5000  activos=3003  marcados=1997  471 426 bytes
después: totalRecords=3003  activos=3003  marcados=0     283 253 bytes  (−40 %)
```

Como **los offsets cambian tras compactar**, el cliente recarga la tabla desde la
primera página y limpia el resaltado en lugar de reutilizar los offsets anteriores.

---

## 7. Pendiente

**Columnas indexables en la UI.** `COLUMNAS_INDEXADAS` en `app.js` replica el valor
por defecto de `Metadata.indexedColumns`. Si el equipo decide exponer esa lista en
`GET /api/status`, el selector de búsqueda debería poblarse desde ahí.
