# Resultados de Benchmarking — Módulo 6

> **Estado:** resultados locales del 26/11/2026. Sirven como evidencia de la
> metodología y del comportamiento actual del motor. No sustituyen una campaña
> de medición en la VM de nube ni una revisión de PACK.

## 1. Entorno de medición

| Elemento | Valor |
|---|---|
| CPU | Intel Core i5-1135G7, 4 núcleos / 8 hilos lógicos |
| RAM | 20.6 GB |
| Sistema operativo | Windows 11 Home, 10.0.26200 |
| JDK | OpenJDK 21.0.6 LTS |
| Disco de trabajo | Directorio temporal local (`%TEMP%`), fuera de OneDrive |
| Dataset | Generado por `MassiveDataGenerator` con seed 42 y 10% de eliminados |

## 2. Datasets

| Dataset | Registros | Activos | Eliminados | Tamaño |
|---|---:|---:|---:|---:|
| 100k | 100,000 | 89,941 | 10,059 | 10.5 MB |
| 500k | 500,000 | 449,722 | 50,278 | 52.5 MB |
| 2M | 2,000,000 | 1,799,070 | 200,930 | 210.0 MB |

Comando de generación:

```powershell
java -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator `
  --records 2000000 `
  --output data/dataset_2m.dat `
  --metadata data/dataset_2m.properties `
  --seed 42 `
  --deleted-percent 10
```

## 3. Metodología

- **T1 PACK:** `SequentialPackEngine` (copia secuencial de registros activos).
- **T2/T4/T8 PACK:** `ParallelPackEngine` de producción.
- **T1 INDEX:** una columna a la vez con `ParallelIndexEngine` (`threads=0` y una
  sola columna), sumando los cuatro tiempos.
- **T2/T4/T8 INDEX:** `ParallelIndexEngine` con todas las columnas.
- **100k:** 1 warmup + 3 repeticiones, mediana.
- **500k y 2M:** 1 repetición sin warmup por tiempo de ejecución; los valores son
  indicativos y tienen mayor varianza.
- Cada corrida usa una copia limpia del dataset en un directorio temporal.
- Speedup = `T1 / Tp`; eficiencia = `Speedup / p`.

## 4. Resultados — 100k (mediana de 3 repeticiones)

### PACK

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 919.94 | 1.000 | 1.000 |
| 2 | 6,224.00 | 0.148 | 0.074 |
| 4 | 4,293.21 | 0.214 | 0.054 |
| 8 | 3,766.93 | 0.244 | 0.031 |

### INDEX

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 11,445.43 | 1.000 | 1.000 |
| 2 | 8,721.63 | 1.312 | 0.656 |
| 4 | 9,110.17 | 1.256 | 0.314 |
| 8 | 4,684.07 | 2.444 | 0.305 |

## 5. Resultados — 500k (1 repetición)

### PACK

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 2,838.53 | 1.000 | 1.000 |
| 2 | 20,859.57 | 0.136 | 0.068 |
| 4 | 149,411.07 | 0.019 | 0.005 |
| 8 | 15,567.34 | 0.182 | 0.023 |

> La corrida de 4 hilos fue un valor atípico. Una repetición posterior del mismo
> caso midió **10,145.76 ms** (speedup 0.316). Aun así, PACK sigue siendo más
> lento que la referencia secuencial.

### INDEX

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 28,421.79 | 1.000 | 1.000 |
| 2 | 20,144.16 | 1.411 | 0.705 |
| 4 | 15,162.14 | 1.875 | 0.469 |
| 8 | 16,741.89 | 1.698 | 0.212 |

## 6. Resultados — 2M (1 repetición)

### PACK

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 12,273.47 | 1.000 | 1.000 |
| 2 | 41,691.52 | 0.294 | 0.147 |
| 4 | 40,904.10 | 0.300 | 0.075 |
| 8 | 54,593.45 | 0.225 | 0.028 |

### INDEX

| Hilos | Tiempo (ms) | Speedup | Eficiencia |
|---:|---:|---:|---:|
| 1 | 125,715.51 | 1.000 | 1.000 |
| 2 | 248,701.74 | 0.506 | 0.253 |
| 4 | 60,662.03 | 2.072 | 0.518 |
| 8 | 85,351.24 | 1.473 | 0.184 |

## 7. Interpretación

### INDEX

La paralelización por columnas sí muestra ganancia:

- 100k: hasta **2.44×** con 8 hilos.
- 500k: hasta **1.87×** con 4 hilos.
- 2M: hasta **2.07×** con 4 hilos.

La máquina tiene 4 núcleos físicos y el índice tiene 4 columnas. Con 8 hilos no
hay más tareas independientes que ejecutar, por lo que la eficiencia cae. Los
resultados de 2 hilos en 2M (0.506) son un valor atípico y deben repetirse con
más warmup.

### PACK

`ParallelPackEngine` **no muestra Speedup** en ninguna de las configuraciones
medidas. La causa probable está en `PartitionWorker`: dentro del bucle de
registros activos se ejecuta:

```java
byte[] buffer = new byte[64 * 1024];
```

Es decir, se asigna un buffer de 64 KB por cada registro activo (más de 1.7
millones de buffers en el dataset de 2M), generando una presión de GC muy alta.
Además, el motor copia el archivo completo a un snapshot antes de particionar y
luego fusiona los bloques. La corrección corresponde a Anderson (módulo 3); este
módulo no modifica su implementación.

## 8. QA — 100k, 2 ciclos

Comando:

```powershell
java -cp bin pe.edu.unmsm.lsdfms.qa.StressTest --records 100000 --cycles 2 --seed 2026
```

Resultados:

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

## 9. Conclusiones para el equipo

1. **INDEX** es el módulo paralelo que hoy demuestra ganancia real.
2. **PACK** necesita una revisión de rendimiento de Anderson antes de presentar
   una tabla de Speedup favorable. El problema no es el dataset ni la medición
   del módulo 6.
3. Los benchmarks de este módulo son **standalone**. Falta la integración de
   PACK con el archivo activo/Metadata para medir el flujo completo del servidor.
4. La VM de nube puede tener otro rendimiento; estos resultados son locales y
   deben repetirse allí para RNF-04.

## 10. Reproducción

```powershell
# Compilar
Get-ChildItem -Recurse -Filter *.java src/main/java |
  ForEach-Object { $_.FullName } | Set-Content sources-main.txt
javac -encoding UTF-8 -d bin -sourcepath src/main/java @sources-main.txt

# Generar
java -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator `
  --records 100000 `
  --output data/dataset_100k.dat `
  --metadata data/dataset_100k.properties `
  --seed 42 --deleted-percent 10

# Verificar
java -cp bin pe.edu.unmsm.lsdfms.generator.DatasetVerifier `
  --data data/dataset_100k.dat --metadata data/dataset_100k.properties

# Benchmark
java -cp bin pe.edu.unmsm.lsdfms.benchmark.BenchmarkRunner `
  --data data/dataset_100k.dat `
  --metadata data/dataset_100k.properties `
  --output results/benchmark_100k.csv `
  --operations pack,index --threads 1,2,4,8 `
  --repetitions 3 --warmup 1

# QA
java -cp bin pe.edu.unmsm.lsdfms.qa.StressTest --records 100000 --cycles 2 --seed 2026
```
