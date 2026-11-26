# Resultados de Benchmarking — Módulo 6

> **Estado:** resultados locales del 26/11/2026, después de integrar PACK con
> el archivo activo y habilitar `threads=1` en INDEX. Sirven como evidencia de
> la metodología y del comportamiento actual del motor. Deben repetirse en la
> VM de nube para RNF-04.

## 1. Entorno de medición

| Elemento | Valor |
|---|---|
| CPU | Intel Core i5-1135G7, 4 núcleos / 8 hilos lógicos |
| RAM | 20.6 GB |
| Sistema operativo | Windows 11 Home, 10.0.26200 |
| JDK | OpenJDK 21.0.6 LTS |
| Disco de trabajo | Directorio temporal local (`%TEMP%`), fuera de OneDrive |
| Dataset | `MassiveDataGenerator`, seed 42, 10% de eliminados |

## 2. Datasets

| Dataset | Registros | Activos | Eliminados | Tamaño |
|---|---:|---:|---:|---:|
| 100k | 100,000 | 89,941 | 10,059 | 10.5 MB |
| 500k | 500,000 | 449,722 | 50,278 | 52.5 MB |
| 2M | 2,000,000 | 1,799,070 | 200,930 | 210.0 MB |

```powershell
java -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator `
  --records 2000000 `
  --output data/dataset_2m.dat `
  --metadata data/dataset_2m.properties `
  --seed 42 --deleted-percent 10
```

## 3. Metodología

- **T1 PACK:** `SequentialPackEngine` (copia secuencial de registros activos).
- **T2/T4/T8 PACK:** `ParallelPackEngine` de producción.
- **T1 INDEX:** `ParallelIndexEngine` con `threads=1` (todas las columnas en un
  solo hilo).
- **T2/T4/T8 INDEX:** `ParallelIndexEngine` con el número de hilos indicado.
- **100k:** 1 warmup + 3 repeticiones, mediana.
- **500k y 2M:** 1 repetición sin warmup por tiempo de ejecución; los valores
  son indicativos y tienen mayor varianza.
- Cada corrida usa una copia limpia del dataset en un directorio temporal.
- Speedup = `T1 / Tp`; eficiencia = `Speedup / p`.

## 4. Resultados — 100k (mediana de 3 repeticiones)

### PACK

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 1,667.76 | 1.000 | 1.000 |
| 2 | 2,287.37 | 0.729 | 0.365 |
| 4 | 2,128.65 | 0.783 | 0.196 |
| 8 | 2,259.46 | 0.738 | 0.092 |

### INDEX

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 18,988.98 | 1.000 | 1.000 |
| 2 | 15,061.90 | 1.261 | 0.630 |
| 4 | 6,649.34 | 2.856 | 0.714 |
| 8 | 6,619.92 | 2.868 | 0.359 |

## 5. Resultados — 500k (1 repetición)

### PACK

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 3,803.25 | 1.000 | 1.000 |
| 2 | 4,594.05 | 0.828 | 0.414 |
| 4 | 4,315.74 | 0.881 | 0.220 |
| 8 | 3,978.44 | 0.956 | 0.119 |

### INDEX

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 34,748.26 | 1.000 | 1.000 |
| 2 | 39,947.51 | 0.870 | 0.435 |
| 4 | 28,978.71 | 1.199 | 0.300 |
| 8 | 31,480.25 | 1.104 | 0.138 |

## 6. Resultados — 2M (1 repetición)

### PACK

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 37,829.35 | 1.000 | 1.000 |
| 2 | 41,662.16 | 0.908 | 0.454 |
| 4 | 54,320.48 | 0.696 | 0.174 |
| 8 | 33,592.56 | 1.126 | 0.141 |

### INDEX

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 163,349.77 | 1.000 | 1.000 |
| 2 | 89,764.02 | 1.820 | 0.910 |
| 4 | 79,240.74 | 2.061 | 0.515 |
| 8 | 70,764.25 | 2.308 | 0.289 |

## 7. Interpretación

### INDEX

La paralelización por columnas muestra ganancia real:

- 100k: hasta **2.87×** con 4–8 hilos.
- 500k: hasta **1.20×** con 4 hilos.
- 2M: hasta **2.31×** con 8 hilos.

La máquina tiene 4 núcleos físicos y el índice tiene 4 columnas. Los resultados
de 500k son los más variables; deben repetirse con warmup y más repeticiones.
Con 8 hilos no hay más tareas independientes que columnas, por lo que la
eficiencia cae.

### PACK

PACK quedó **integrado**: el archivo compactado reemplaza al activo y la
Metadata se reconstruye antes de INDEX. También se optimizó `PartitionWorker`
para reutilizar un buffer y usar flujo secuencial en lugar de un `seek` por
registro.

Aun así, PACK no muestra una ganancia estable:

- 100k y 500k: el coste de snapshot, particionado, fusión y publicación domina
  frente al trabajo útil.
- 2M con 8 hilos: **1.13×**, la única configuración claramente por encima de T1.
- Los resultados de 4 hilos en 2M y de 2 hilos en 500k son valores atípicos.

Para una conclusión defendible se debe repetir la campaña con más warmup, en
disco local sin antivirus/OneDrive y, preferentemente, en la VM de nube.

## 8. QA — 100k, 2 ciclos

```powershell
java -cp bin pe.edu.unmsm.lsdfms.qa.StressTest --records 100000 --cycles 2 --seed 2026
```

| Etapa | Registros | Activos | Eliminados | Checks de índice | Errores |
|---|---:|---:|---:|---:|---:|
| Ciclo 1: INDEX | 104,747 | 94,951 | 9,796 | 16,000 | 0 |
| Ciclo 1: INDEX post-PACK | 94,951 | 94,951 | 0 | 16,000 | 0 |
| Ciclo 2: INDEX | 99,698 | 94,951 | 4,747 | 16,000 | 0 |
| Ciclo 2: INDEX post-PACK | 94,951 | 94,951 | 0 | 16,000 | 0 |

- Reducción de tamaño del PACK: 9.35% en el ciclo 1 y 4.76% en el ciclo 2.
- Conteos, offsets, ids, búsquedas por id y por columnas de texto: sin errores.
- La verificación de índice muestrea 10,000 ids y 2,000 valores por columna de
  texto indexada; los conteos y el recorrido del archivo son completos.

## 9. Integración verificada

Las siguientes pruebas pasan en local:

- `PackEngineAdapterTest`: PACK reemplaza el archivo activo, reconstruye Metadata,
  conserva activos y permite INDEX/búsqueda.
- `ServerIntegrationTest`: POST, GET, PUT, DELETE, búsqueda por id, PACK + INDEX
  y estado de sincronización por HTTP.
- `IntegrityCheckerTest`: ciclo borrado/inserción/PACK/INDEX sin errores.
- `MassiveDataGeneratorTest`, `BenchmarkSmokeTest`, pruebas de storage, PACK,
  INDEX y cola.

## 10. Reproducción

```powershell
Get-ChildItem -Recurse -Filter *.java src/main/java |
  ForEach-Object { $_.FullName } | Set-Content sources-main.txt
javac -encoding UTF-8 -d bin -sourcepath src/main/java @sources-main.txt

java -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator `
  --records 100000 --output data/dataset_100k.dat `
  --metadata data/dataset_100k.properties --seed 42 --deleted-percent 10

java -cp bin pe.edu.unmsm.lsdfms.benchmark.BenchmarkRunner `
  --data data/dataset_100k.dat --metadata data/dataset_100k.properties `
  --output results/benchmark_100k.csv --operations pack,index `
  --threads 1,2,4,8 --repetitions 3 --warmup 1

java -cp bin pe.edu.unmsm.lsdfms.qa.StressTest `
  --records 100000 --cycles 2 --seed 2026
```
