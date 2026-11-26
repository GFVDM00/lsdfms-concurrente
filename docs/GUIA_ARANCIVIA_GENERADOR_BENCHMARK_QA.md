# Módulo 6 — Generador de Datos Masivos, Benchmarking y QA (Arancivia)

Cubre **RNF-03** (escalabilidad y métricas de rendimiento) y el aseguramiento de
calidad del repositorio: generación de datasets masivos, medición de *Speedup* y
eficiencia de `PACK()` e `INDEX()`, y verificación de integridad referencial.

---

## 1. Generador de datasets masivos

`pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator`

```bash
# java -cp bin ...MassiveDataGenerator [registros] [archivo] [ratioBorrados] [semilla]
java -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator 1000000 data/policies_data.dat 0.30
```

Escribe directamente el formato binario de RF-01 (`flag 1B + length 4B + payload`)
en lugar de insertar registro a registro: con 2,000,000 de filas, el camino de
inserción con persistencia de metadata por registro sería inviable. La metadata no
se escribe aquí — `RafStorageEngine` la reconstruye al abrir el archivo, que es la
fuente de verdad.

- Longitud variable real: titulares de 2 apellidos, tipos de seguro y departamentos
  de distinta longitud en UTF-8. Media observada: **~94 bytes/registro**.
- `ratioBorrados` marca un porcentaje con la bandera `0x01` para que `PACK()` tenga
  trabajo real que descartar.
- `semilla` fija hace los datasets reproducibles entre corridas.

| Lote | Tamaño aproximado | Tiempo de generación |
|---|---|---|
| 100,000 | ~9 MB | ~0,3 s |
| 500,000 | ~45 MB | ~1,5 s |
| 2,000,000 | ~180 MB | ~6 s |

---

## 2. Línea base secuencial

Para medir Speedup hace falta `T(1)`. Ambos motores paralelos aceptan ahora
`threads = 1`, que ejecuta **el mismo algoritmo con un único hilo de trabajo**:

- `ParallelPackEngine` con 1 hilo genera una sola partición que cubre el archivo
  completo y la procesa con un único worker.
- `ParallelIndexEngine` con 1 hilo construye los índices de las columnas uno tras
  otro en serie.

Comparar contra el mismo código con `P=1` aísla el efecto del paralelismo; una
implementación secuencial distinta introduciría diferencias ajenas a él.

---

## 3. Benchmarking

`pe.edu.unmsm.lsdfms.benchmark.BenchmarkRunner`

```bash
# java -cp bin ...BenchmarkRunner [registros] [repeticiones] [csvSalida]
java -Xmx2g -cp bin pe.edu.unmsm.lsdfms.benchmark.BenchmarkRunner 1000000 3 docs/benchmark.csv
```

Genera el dataset, hace una corrida de calentamiento (descartada, para no medir la
compilación JIT), ejecuta cada configuración `repeticiones` veces y reporta el mejor
tiempo. Verifica además que todas las configuraciones conserven exactamente el mismo
número de registros: una ganancia de velocidad que pierda datos no es una ganancia.

### Resultados de referencia (150,000 registros · 30% marcados · 12 núcleos)

**`PACK()` — paralelismo a nivel de datos**

| Hilos | Tiempo | Speedup | Eficiencia |
|---|---|---|---|
| 1 (base) | 6475 ms | — | — |
| 2 | 5080 ms | 1,27× | 63,7% |
| 4 | 4230 ms | 1,53× | 38,3% |
| 8 | 3858 ms | 1,68× | 21,0% |

**`INDEX()` — paralelismo a nivel de tareas**

| Hilos | Tiempo | Speedup | Eficiencia |
|---|---|---|---|
| 1 (base) | 12212 ms | — | — |
| 2 | 7456 ms | 1,64× | 81,9% |
| 4 | 4117 ms | 2,97× | 74,2% |
| 8 | 4075 ms | 3,00× | 37,5% |

### Lectura de los resultados

- **`INDEX()` escala mejor que `PACK()`**: construir y ordenar el índice de cada
  columna es trabajo de CPU independiente, mientras que `PACK()` está dominado por
  E/S secuencial (copia del snapshot y fusión final de bloques), que no se
  paraleliza.
- **`INDEX()` se satura en 4 hilos** (2,97× → 3,00×): solo hay **4 columnas
  indexadas**, de modo que con 8 hilos cuatro quedan ociosos. El paralelismo a nivel
  de tareas está limitado por el número de tareas disponibles, no por los núcleos.
  Para aprovechar 8 hilos habría que indexar 8 columnas o partir el ordenamiento de
  cada columna.
- La **eficiencia cae al crecer P** en ambos casos, como predice la ley de Amdahl:
  la fracción secuencial (snapshot, fusión, publicación atómica) fija el techo.

---

## 4. QA — integridad referencial

`pe.edu.unmsm.lsdfms.qa.QaSuite` y `pe.edu.unmsm.lsdfms.qa.IntegrityVerifier`

```bash
# java -cp bin ...QaSuite [registros] [ciclos]
java -cp bin pe.edu.unmsm.lsdfms.qa.QaSuite 20000 3
```

Cada ciclo marca ~20% de los registros activos para borrado lógico, ejecuta `PACK()`
con 4 hilos, reconstruye los índices y verifica:

1. El archivo se recorre de cabecera en cabecera sin registros truncados ni banderas
   inválidas.
2. Los contadores de `Metadata` (`totalRecords`, `activeRecords`, `deletedRecords`,
   `lastOffset`) coinciden con el contenido real del archivo.
3. Una muestra de registros activos se resuelve correctamente por búsqueda
   dicotómica en cada índice: la clave encontrada apunta a un offset que contiene
   realmente ese registro. Un índice con offsets obsoletos se delata aquí.
4. Tras `PACK()` no queda ningún registro marcado para borrado.
5. `PACK()` deja los índices marcados como desactualizados (los offsets cambiaron).

Resultado de la corrida de referencia (20,000 registros, 3 ciclos):
20,000 → 16,000 → 12,800 → 10,240 registros, **integridad verificada en todos los
ciclos**.

---

## 5. Integración de `PACK()` en el servidor

`PACK()` ya se ejecuta de verdad en el ciclo de mantenimiento (RF-07).
`ParallelPackEngine` nunca reemplaza su archivo de origen — escribe el compactado
aparte —, así que el paso de publicación lo añade
`pe.edu.unmsm.lsdfms.pack.StoragePackAdapter`:

1. Compacta el archivo activo hacia un hermano `.packed`.
2. `StorageEngine.replaceDataFile(...)` cierra el descriptor, reemplaza el archivo
   de forma atómica, lo reabre y reconstruye offsets y contadores desde el contenido
   nuevo, dejando los índices marcados como desactualizados.
3. Si algo falla, el archivo activo queda intacto y el temporal se descarta.

El descriptor se cierra antes de mover el archivo porque en Windows un handle
abierto impide reemplazar el destino. Solo lo invoca el hilo del `QueueDispatcher`,
único escritor del archivo, por lo que no hay mutaciones concurrentes (RNF-02).

Verificación extremo a extremo desde la API (5000 registros, 40% marcados):

```
antes:   totalRecords=5000  activos=3003  marcados=1997  471,426 bytes
después: totalRecords=3003  activos=3003  marcados=0     283,253 bytes  (−40%)
```
