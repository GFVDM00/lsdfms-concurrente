# LSDFMS — Large Scale Data File Management System
> **Programación Concurrente y Paralela — Trabajo de Aplicación**  
> *Indexación y Compactación de Datos a Gran Escala con Java Nativo (JDK 22+)*

---

## 📌 Objetivo del Proyecto
Diseñar e implementar desde cero en **Java estándar (JDK)** un Sistema de Gestión de Archivos de Datos a Gran Escala (**LSDFMS**) con procesamiento concurrente y paralelo, conectado a una aplicación web cliente que evidencie un caso real de negocio.

---

## 👥 Repartición de Módulos y Responsabilidades (Equipo de 6 Integrantes)

| # | Integrante | Rol y Módulo Asignado | Responsabilidad Principal |
|---|------------|------------------------|---------------------------|
| 1 | **Alejandro** | **Integración General, Motor LSDFMS y Metadata** | Lidera contratos e interfaces. Implementa el gestor de almacenamiento de bajo nivel sobre `RandomAccessFile` para lectura y escritura directa mediante *offsets* exactos (`seek()`), registros y campos de longitud variable con bandera de borrado lógico, y repositorio de `Metadata`. |
| 2 | **Brandon** | **Servidor TCP/HTTP, Cola Sincronizada y Cloud** | Servidor nativo con `com.sun.net.httpserver.HttpServer` / `ServerSocket` (sin frameworks externos), Cola Concurrente (`ThreadSafeRequestQueue`) con `ReentrantLock` y `Condition`, y despliegue en la nube (AWS/GCP/Azure). |
| 3 | **Anderson** | **Operación Paralela `PACK()` (Compactación)** | Paralelismo a nivel de datos: particionamiento lógico en $P$ segmentos balanceados sin cortar registros, hilos trabajadores que filtran registros eliminados y fusión limpia secuencial/ordenada hacia el nuevo archivo. |
| 4 | **Jerson** | **Operación Paralela `INDEX()` (Indexación)** | Paralelismo a nivel de tareas: estructura binaria/ordenada de índices ($I_1 \dots I_N$), búsqueda dicotómica $O(\log n)$, y despachador paralelo con un hilo independiente por columna indexable sin contención. |
| 5 | **Cicilis** | **Cliente Web y Modelado de Caso de Negocio** | Definición de la entidad del dominio, interfaz web minimalista (HTML/CSS/JS nativo) consumiendo API REST, operaciones CRUD, indicadores en vivo de estado (*Sincronizado* vs *Desactualizado*). |
| 6 | **Arancivia** | **Generador Masivo de Datos, Benchmarking y QA** | Generador de datasets masivos sintéticos (100k, 500k, 2M+ registros), cálculo de métricas de rendimiento (*Speedup* y eficiencia con 2, 4 y 8 hilos), y pruebas de estrés/integridad referencial. |

---

## 🌿 Política de Ramas y Flujo Git (Trabajo Concurrente)

Para evitar bloqueos y colisiones de código entre los 6 integrantes:
1. **Ramas independientes:** Cada integrante trabaja exclusivamente en su propia rama (`feature/modulo-X-...`).
2. **Prohibido el Auto-Merge:** Ningún integrante puede aprobar o mergear su propio PR. Los PRs de los módulos deben ser revisados y aprobados por **Alejandro** (Líder de Integración), y el PR de Alejandro debe ser aprobado por otro integrante.
3. **Validación Automática (CI):** Cada PR ejecuta automáticamente un flujo de GitHub Actions que compila y verifica los tests de concurrencia antes de habilitar el merge.
4. **Detalles y configuración de GitHub:** Ver [`docs/POLITICA_GIT_Y_COLABORACION.md`](docs/POLITICA_GIT_Y_COLABORACION.md).

| Rama | Responsable | Módulo |
|---|---|---|
| `main` | **Alejandro** (Integrador) | Rama protegida y estable |
| `feature/modulo-1-storage-metadata` | Alejandro | Motor LSDFMS y Metadata |
| `feature/modulo-2-server-queue` | Brandon | Servidor HTTP y Cola Sincronizada |
| `feature/modulo-3-pack-parallel` | Anderson | Compactación PACK() Paralela |
| `feature/modulo-4-index-parallel` | Jerson | Indexación INDEX() Paralela |
| `feature/modulo-5-web-client` | Cicilis | Cliente Web Minimalista |
| `feature/modulo-6-generator-qa` | Arancivia | Generador Masivo y QA |

---

## 🏛️ Estructura del Repositorio

```text
lsdfms-concurrente/
├── docs/                                 # Especificaciones técnicas y guías de trabajo por integrante
│   ├── ESPECIFICACION_FORMATO_BINARIO.md # Especificación del formato de archivo y offsets
│   └── GUIA_ALEJANDRO_MOTOR_METADATA.md  # Instrucciones para el primer encargado (Alejandro)
├── data/                                 # Directorio para archivos .dat, .idx y metadata.properties (ignorado en git)
├── src/
│   └── main/
│       ├── java/
│       │   └── pe/edu/unmsm/lsdfms/
│       │       ├── model/                # Entidad de negocio (InsurancePolicy) y esquemas
│       │       ├── storage/              # [Alejandro] RandomAccessFile, OffsetManager, MetadataRepository
│       │       ├── queue/                # [Brandon] ThreadSafeRequestQueue, RequestCommand
│       │       ├── server/               # [Brandon] NativeHttpServer, Handlers REST
│       │       ├── pack/                 # [Anderson] ParallelPackEngine, DataPartitioner, BlockMerger
│       │       ├── index/                # [Jerson] ParallelIndexEngine, BinaryIndexSearcher, ColumnTask
│       │       ├── generator/            # [Arancivia] MassiveDataGenerator, DatasetVerifier
│       │       ├── benchmark/            # [Arancivia] BenchmarkRunner, SpeedupMetrics
│       │       ├── qa/                   # [Arancivia] IntegrityChecker, StressTest
│       │       └── Main.java             # Punto de entrada / Bootstrapper
│       └── resources/
│           └── public/                   # [Cicilis] Interfaz Web (index.html, app.js, styles.css)
├── .gitignore
└── README.md
```

---

## 📋 Requerimientos del Sistema

### Funcionales (RF)
- **RF-01 (Estructura LSDFMS):** Archivos físicos de acceso directo con registros y campos de longitud variable gobernados por Metadata y desplazamientos (*offsets*).
- **RF-02 (Acceso Aleatorio y Búsqueda $O(\log n)$):** Archivos de índices binarios ordenados que referencian las posiciones físicas exactas de los registros.
- **RF-03 (CRUD desde Cliente Web):** Inserción, modificación, marcado de eliminación lógica y búsquedas desde interfaz web minimalista.
- **RF-04 (Cola Sincronizada):** Cola concurrente (`ThreadSafeRequestQueue`) en el servidor para serializar mutaciones.
- **RF-05 (PACK Paralelo):** Compactación física con paralelismo a nivel de datos (particionar, filtrar en paralelo y fusionar).
- **RF-06 (INDEX Paralelo):** Reindexación paralela a nivel de tareas (un hilo por columna/campo indexado).
- **RF-07 (Mantenimiento y Nube):** Ejecución coordinada de `PACK()` e `INDEX()`, sincronizando el estado con el cliente web.

### No Funcionales y Restricciones Técnicas (RNF)
- **RNF-01 (Nativo Java sin Frameworks):** Prohibido usar motores de bases de datos, ORMs o librerías de indexación externa. Uso exclusivo del JDK estándar (`RandomAccessFile`, `ExecutorService`, `ReentrantLock`, `synchronized`).
- **RNF-02 (Seguridad de Hilos):** Exclusión mutua, sin condiciones de carrera.
- **RNF-03 (Escalabilidad):** Soportar volúmenes masivos ($\ge 1{,}000{,}000$ registros) demostrando ganancia real (*Speedup* y eficiencia).
- **RNF-04 (Despliegue Cloud):** Servidor operable en VM de nube (AWS EC2 / Azure VM / Google Cloud Compute Engine).
- **RNF-05 (Caso Real de Aplicación):** Dominio de **Pólizas de Seguros y Asegurados (`InsurancePolicy`)**.

---

## 🛠️ Compilación y Ejecución

```bash
# Compilar todo el proyecto sin dependencias externas
javac -d bin -sourcepath src/main/java $(find src/main/java -name "*.java")

# Ejecutar el sistema
java -cp bin pe.edu.unmsm.lsdfms.Main
```

### Prueba de regresión del motor (sin dependencias externas)

```bash
javac -d bin -sourcepath src/main/java:src/test/java \
  $(find src/main/java src/test/java -name "*.java")
java -cp bin pe.edu.unmsm.lsdfms.storage.RafStorageEngineRegressionTest
```

La metadata del motor se persiste en `data/metadata.properties`. Los archivos de
índice (`.idx`) y su construcción pertenecen al módulo de indexación; el motor
solo conserva en metadata el estado de sincronización y las columnas configuradas.

---

## 📊 Módulo 6: Generador, Benchmarking y QA

El módulo de Arancivia se ejecuta por consola y no depende del cliente web:

```bash
# Generar un dataset de 100k con 10% de eliminados
java -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator \
  --records 100000 \
  --output data/dataset_100k.dat \
  --metadata data/dataset_100k.properties \
  --seed 42 --deleted-percent 10

# Verificar el dataset
java -cp bin pe.edu.unmsm.lsdfms.generator.DatasetVerifier \
  --data data/dataset_100k.dat --metadata data/dataset_100k.properties

# Benchmark PACK/INDEX con 1, 2, 4 y 8 hilos
java -cp bin pe.edu.unmsm.lsdfms.benchmark.BenchmarkRunner \
  --data data/dataset_100k.dat \
  --metadata data/dataset_100k.properties \
  --output results/benchmark_100k.csv \
  --operations pack,index --threads 1,2,4,8 \
  --repetitions 3 --warmup 1

# Prueba de estrés e integridad
java -cp bin pe.edu.unmsm.lsdfms.qa.StressTest \
  --records 100000 --cycles 2 --seed 2026
```

Documentación detallada:

- [`docs/GUIA_ARANCIVIA_GENERADOR_BENCHMARK_QA.md`](docs/GUIA_ARANCIVIA_GENERADOR_BENCHMARK_QA.md)
- [`docs/RESULTADOS_BENCHMARK.md`](docs/RESULTADOS_BENCHMARK.md)

> **Nota:** PACK todavía no está integrado con el servidor y, con el código
> actual, su versión paralela no muestra ganancia frente a la secuencial. La
> corrección de rendimiento corresponde al módulo 3; este módulo documenta el
> hallazgo y mantiene sus benchmarks standalone.
