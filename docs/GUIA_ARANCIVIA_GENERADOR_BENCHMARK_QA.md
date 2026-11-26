# Módulo 6 — Generador masivo, Benchmarking y QA

**Responsable:** Arancivia

**Requerimientos cubiertos:** RNF-03 (escalabilidad y métricas), apoyo a RF-01/RF-05/RF-06 y QA de integridad.

Este módulo es independiente del cliente web y del servidor HTTP. Se ejecuta
por consola contra las clases Java del motor de almacenamiento, PACK e INDEX.

---

## 1. Componentes

```text
src/main/java/pe/edu/unmsm/lsdfms/
├── generator/
│   ├── DatasetSpec.java
│   ├── PolicyFactory.java
│   ├── MassiveDataGenerator.java
│   ├── GenerationResult.java
│   └── DatasetVerifier.java
├── benchmark/
│   ├── BenchmarkConfig.java
│   ├── BenchmarkRunner.java
│   ├── BenchmarkResult.java
│   ├── SpeedupMetrics.java
│   ├── BenchmarkStorage.java
│   ├── SequentialPackEngine.java
│   └── SequentialPackResult.java
└── qa/
    ├── IntegrityChecker.java
    ├── IntegrityReport.java
    └── StressTest.java
```

### Generador

- Escribe directamente el formato binario documentado:
  `flag (1 byte) | recordLength (4 bytes big-endian) | payload`.
- Respeta el orden de campos de `RafStorageEngine`:
  `id`, `dni`, `titular`, `tipoSeguro`, `departamento`, `prima`, `fechaEmision`.
- Usa `SplittableRandom` con semilla: el mismo seed produce el mismo `.dat`.
- Genera DNI únicos de 8 dígitos y nombres/apellidos de longitud variable.
- Escribe Metadata una sola vez al final (no persiste metadata por registro).
- Permite marcar un porcentaje de registros como eliminados lógicamente para
  alimentar los benchmarks de PACK.

### Benchmarking

- **PACK:** T1 con `SequentialPackEngine` (implementación secuencial de
  referencia) y T2/T4/T8 con `ParallelPackEngine` de producción.
- **INDEX:** T1 ejecuta una columna a la vez con `ParallelIndexEngine`
  (`threads=0` y una sola columna = un hilo). T2/T4/T8 ejecutan todas las
  columnas en paralelo con el motor de producción.
- Cada corrida usa una copia limpia del dataset en un directorio temporal; el
  tiempo de copia no se mide.
- Se descarta el warmup y se usa la mediana de las repeticiones.
- Salida CSV con tiempo, Speedup y eficiencia.

> **Limitación de INDEX:** el motor de Jerson no expone `threads=1`. El baseline
> T1 usa el mismo motor con una columna a la vez; es una comparación de tareas
> equivalente (4 columnas secuenciales vs. 4 columnas concurrentes), no una
> opción oficial `threads=1`.

### QA

- `IntegrityChecker` recorre todo el archivo y valida:
  - cabeceras, longitudes, flags y offsets;
  - deserialización de cada payload;
  - conteos y `lastOffset` contra Metadata;
  - unicidad de ids y offsets;
  - búsquedas por id y por columnas de texto en los índices.
- Para datasets grandes, las comprobaciones de índice se muestrean de forma
  sistemática (10,000 ids y 2,000 valores por columna de texto indexada);
  los conteos y el recorrido del archivo son completos.
- `StressTest` ejecuta ciclos:
  `borrado lógico + inserción -> INDEX -> verificación -> PACK -> INDEX -> verificación`.

---

## 2. Compilación

```powershell
Get-ChildItem -Recurse -Filter *.java src/main/java |
  ForEach-Object { $_.FullName } | Set-Content sources-main.txt
javac -encoding UTF-8 -d bin -sourcepath src/main/java @sources-main.txt

Get-ChildItem -Recurse -Filter *.java src/test/java |
  ForEach-Object { $_.FullName } | Set-Content sources-test.txt
javac -encoding UTF-8 -d bin -cp bin -sourcepath src/test/java @sources-test.txt
```

En Linux/macOS:

```bash
javac -encoding UTF-8 -d bin -sourcepath src/main/java \
  $(find src/main/java -name "*.java")
javac -encoding UTF-8 -d bin -cp bin -sourcepath src/test/java \
  $(find src/test/java -name "*.java")
```

---

## 3. Generador

### Uso

```powershell
java -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator `
  --records 100000 `
  --output data/dataset_100k.dat `
  --metadata data/dataset_100k.properties `
  --seed 42 `
  --deleted-percent 10
```

Opciones:

| Opción | Descripción |
|---|---|
| `--records N` | Cantidad de registros |
| `--output PATH` | Archivo `.dat` de salida |
| `--metadata PATH` | Archivo `.properties` de metadata |
| `--seed N` | Semilla reproducible |
| `--deleted-percent N` | Porcentaje de registros con flag `0x01` |

### Verificación

```powershell
java -cp bin pe.edu.unmsm.lsdfms.generator.DatasetVerifier `
  --data data/dataset_100k.dat `
  --metadata data/dataset_100k.properties
```

### Tamaños de referencia

| Dataset | Registros | Activos | Eliminados | Bytes |
|---|---:|---:|---:|---:|
| 100k | 100,000 | 89,941 | 10,059 | 10,501,143 |
| 500k | 500,000 | 449,722 | 50,278 | 52,498,035 |
| 2M | 2,000,000 | 1,799,070 | 200,930 | 209,979,582 |

> Estos datasets se generan localmente y están cubiertos por `.gitignore`
> (`data/*.dat`, `data/*.properties`). No se versionan archivos masivos.

---

## 4. Benchmarking

### Uso

```powershell
java -cp bin pe.edu.unmsm.lsdfms.benchmark.BenchmarkRunner `
  --data data/dataset_100k.dat `
  --metadata data/dataset_100k.properties `
  --output results/benchmark_100k.csv `
  --operations pack,index `
  --threads 1,2,4,8 `
  --repetitions 3 `
  --warmup 1
```

Opciones:

| Opción | Descripción |
|---|---|
| `--operations` | `pack`, `index` o ambas |
| `--threads` | `1,2,4,8` |
| `--repetitions` | Repeticiones medidas |
| `--warmup` | Corridas descartadas antes de medir |
| `--output` | CSV de resultados |

### Metodología

- Ejecutar sobre disco local, no sobre una carpeta sincronizada por OneDrive.
- Usar la misma JVM, los mismos flags y el mismo dataset para todos los hilos.
- T1 = referencia secuencial.
- Speedup = `T1 / Tp`; eficiencia = `Speedup / p`.
- Para INDEX, T1 suma el tiempo de indexar cada columna por separado.
- Para PACK, T1 usa `SequentialPackEngine`; T2/T4/T8 usan `ParallelPackEngine`.
- El warmup y las copias del dataset quedan fuera del tiempo medido.

Los resultados completos están en [`RESULTADOS_BENCHMARK.md`](RESULTADOS_BENCHMARK.md).

---

## 5. QA y estrés

### Prueba automatizada rápida

```powershell
java -ea -cp bin pe.edu.unmsm.lsdfms.qa.IntegrityCheckerTest
```

### Prueba de estrés configurable

```powershell
java -cp bin pe.edu.unmsm.lsdfms.qa.StressTest `
  --records 100000 `
  --cycles 2 `
  --seed 2026
```

El comando anterior genera un dataset temporal, ejecuta ciclos de borrado,
inserción, INDEX, PACK y verificación, y elimina el directorio al terminar.
Con `--keep` conserva el directorio para inspección.

Resultado de referencia: 100,000 registros, 2 ciclos, 16,000 comprobaciones de
índice por etapa y **0 errores**.

---

## 6. Pruebas

```powershell
java -ea -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGeneratorTest
java -ea -cp bin pe.edu.unmsm.lsdfms.benchmark.BenchmarkSmokeTest
java -ea -cp bin pe.edu.unmsm.lsdfms.qa.IntegrityCheckerTest
```

Las tres pruebas usan datasets pequeños y no dependen de servicios externos.

---

## 7. Limitaciones y dependencias externas

1. **PACK paralelo no muestra ganancia con el código actual.** En las mediciones
   de este módulo, `ParallelPackEngine` fue más lento que la referencia
   secuencial. La causa probable está en `PartitionWorker`: asigna un
   `byte[64 * 1024]` nuevo por cada registro activo, lo que genera una presión
   de GC muy alta. Corregirlo corresponde a Anderson (módulo 3); este módulo no
   modifica su código.
2. **PACK no está integrado al servidor ni actualiza el archivo activo o la
   Metadata.** El benchmark es standalone, a nivel de motor. La integración
   coordinada corresponde a Alejandro/Anderson.
3. **INDEX no tiene `threads=1` oficial.** El baseline T1 se construyó con una
   columna a la vez usando el mismo motor. Si Jerson expone `threads=1`, se
   puede reemplazar el baseline sin cambiar el runner.
4. **INDEX con 8 hilos y 4 columnas:** solo hay 4 tareas; los hilos adicionales
   no aportan paralelismo. Por eso la eficiencia con 8 hilos es baja.
5. **El cliente web no es dependencia de este módulo.** La interfaz de Cicilis
   puede consumir después los CSV o los datasets, pero el generador, los
   benchmarks y el QA se ejecutan por consola.
