package pe.edu.unmsm.lsdfms.benchmark;

import pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator;
import pe.edu.unmsm.lsdfms.index.ParallelIndexEngine;
import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.pack.PackResult;
import pe.edu.unmsm.lsdfms.pack.ParallelPackEngine;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mide PACK() e INDEX() con 1, 2, 4 y 8 hilos sobre el mismo dataset y calcula
 * Speedup y eficiencia (RNF-03).
 *
 *   java -Xmx2g -cp bin pe.edu.unmsm.lsdfms.benchmark.BenchmarkRunner 1000000 3 docs/benchmark.csv
 */
public final class BenchmarkRunner {

    private static final int[] THREAD_COUNTS = {1, 2, 4, 8};
    private static final double DELETED_RATIO = 0.30;

    private final Path workspace;
    private final long records;
    private final int repetitions;

    private final List<SpeedupMetrics.Measurement> packMeasurements = new ArrayList<>();
    private final List<SpeedupMetrics.Measurement> indexMeasurements = new ArrayList<>();

    public BenchmarkRunner(Path workspace, long records, int repetitions) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.records = records;
        this.repetitions = Math.max(1, repetitions);
    }

    public static void main(String[] args) throws Exception {
        long records = args.length > 0 ? Long.parseLong(args[0]) : 200_000L;
        int repetitions = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        Path csv = args.length > 2 ? Path.of(args[2]) : null;

        BenchmarkRunner runner = new BenchmarkRunner(Path.of("data", "benchmark"), records, repetitions);
        runner.run();
        runner.printReport();
        if (csv != null) {
            runner.exportCsv(csv);
            System.out.println("Resultados exportados a: " + csv.toAbsolutePath().normalize());
        }
    }

    public void run() throws IOException, InterruptedException {
        Files.createDirectories(workspace);
        Path dataset = workspace.resolve("benchmark_data.dat");

        System.out.println("==================================================================");
        System.out.println("  BENCHMARK DE PACK() e INDEX() - Modulo 6 (Arancivia)            ");
        System.out.println("  RNF-03: Speedup y eficiencia frente a la version secuencial      ");
        System.out.println("==================================================================");
        System.out.printf("Procesadores disponibles : %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("Memoria maxima de la JVM : %,d MB%n",
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.printf("Registros del dataset    : %,d (%.0f%% marcados para borrado)%n",
                records, DELETED_RATIO * 100);
        System.out.printf("Repeticiones por medida  : %d (se reporta el mejor tiempo)%n%n", repetitions);

        System.out.println("[1/3] Generando dataset sintetico de longitud variable...");
        MassiveDataGenerator.Result generated =
                MassiveDataGenerator.generate(dataset, records, DELETED_RATIO, 20261126L);
        System.out.printf("      %,d registros | %.2f MB | %,d ms%n%n",
                generated.getRecords(), generated.getBytes() / (1024.0 * 1024.0),
                generated.getElapsedMillis());

        System.out.println("[2/3] Midiendo PACK() (paralelismo a nivel de datos)...");
        measurePack(dataset, generated);

        System.out.println();
        System.out.println("[3/3] Midiendo INDEX() (paralelismo a nivel de tareas)...");
        measureIndex(dataset);
    }

    private void measurePack(Path dataset, MassiveDataGenerator.Result generated)
            throws IOException, InterruptedException {
        ParallelPackEngine engine = new ParallelPackEngine();
        Path output = workspace.resolve("benchmark_data.packed");

        // Calentamiento descartado: evita medir la compilacion JIT.
        Files.deleteIfExists(output);
        engine.pack(dataset, output, 1);
        Files.deleteIfExists(output);

        for (int threads : THREAD_COUNTS) {
            long best = Long.MAX_VALUE;
            long kept = 0;
            for (int r = 0; r < repetitions; r++) {
                Files.deleteIfExists(output);
                PackResult result = engine.pack(dataset, output, threads);
                best = Math.min(best, result.getElapsedMillis());
                kept = result.getActiveRecords();

                if (result.getOriginalRecords() != generated.getRecords()
                        || result.getActiveRecords() != generated.getActiveRecords()) {
                    throw new IOException("PACK con " + threads + " hilos produjo un conteo distinto: "
                            + result.getActiveRecords() + " activos frente a "
                            + generated.getActiveRecords() + " esperados");
                }
            }
            Files.deleteIfExists(output);
            packMeasurements.add(new SpeedupMetrics.Measurement("PACK", threads, best, records));
            System.out.printf("      %d hilo%s: %,6d ms  (%,d registros conservados)%n",
                    threads, threads == 1 ? " " : "s", best, kept);
        }
    }

    private void measureIndex(Path dataset) throws IOException, InterruptedException {
        StorageEngine view = new DatasetView(dataset);

        new ParallelIndexEngine(workspace, 1).rebuildAllIndexes(view); // calentamiento

        for (int threads : THREAD_COUNTS) {
            long best = Long.MAX_VALUE;
            long indexed = 0;
            for (int r = 0; r < repetitions; r++) {
                ParallelIndexEngine engine = new ParallelIndexEngine(workspace, threads);
                long started = System.nanoTime();
                engine.rebuildAllIndexes(view);
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                best = Math.min(best, elapsed);
                indexed = engine.getLastResult().getRecordsIndexed();
            }
            indexMeasurements.add(new SpeedupMetrics.Measurement("INDEX", threads, best, records));
            System.out.printf("      %d hilo%s: %,6d ms  (%,d entradas por columna)%n",
                    threads, threads == 1 ? " " : "s", best, indexed);
        }
    }

    public void printReport() {
        System.out.println();
        System.out.println("==================================================================");
        System.out.println("  TABLA COMPARATIVA DE RENDIMIENTO                                ");
        System.out.println("==================================================================");
        printTable("PACK()  - paralelismo a nivel de datos", packMeasurements);
        System.out.println();
        printTable("INDEX() - paralelismo a nivel de tareas", indexMeasurements);
        System.out.println();
        System.out.println("S(p) = T(1)/T(p)   |   E(p) = S(p)/p   |   f = fraccion paralela (Amdahl)");
        System.out.println("==================================================================");
    }

    private void printTable(String title, List<SpeedupMetrics.Measurement> measurements) {
        System.out.println(title);
        System.out.println("  +--------+------------+-----------+------------+-----------+------------+");
        System.out.println("  | Hilos  | Tiempo(ms) |  Speedup  | Eficiencia | reg/s     | f (Amdahl) |");
        System.out.println("  +--------+------------+-----------+------------+-----------+------------+");

        long baseline = measurements.isEmpty() ? 0 : measurements.get(0).getElapsedMillis();
        for (SpeedupMetrics.Measurement m : measurements) {
            double s = SpeedupMetrics.speedup(baseline, m.getElapsedMillis());
            double e = SpeedupMetrics.efficiency(baseline, m.getElapsedMillis(), m.getThreads());
            double f = SpeedupMetrics.amdahlParallelFraction(s, m.getThreads());
            System.out.printf("  | %-6d | %,10d | %9s | %10s | %9s | %10s |%n",
                    m.getThreads(), m.getElapsedMillis(),
                    m.getThreads() == 1 ? "base" : SpeedupMetrics.formatDouble(s) + "x",
                    m.getThreads() == 1 ? "-" : SpeedupMetrics.formatPercent(e),
                    Double.isNaN(m.throughput()) ? "n/d" : String.format("%,.0f", m.throughput()),
                    m.getThreads() == 1 ? "-" : SpeedupMetrics.formatDouble(f));
        }
        System.out.println("  +--------+------------+-----------+------------+-----------+------------+");
    }

    public void exportCsv(Path csv) throws IOException {
        Path file = csv.toAbsolutePath().normalize();
        if (file.getParent() != null) Files.createDirectories(file.getParent());

        Map<String, List<SpeedupMetrics.Measurement>> grupos = new LinkedHashMap<>();
        grupos.put("PACK", packMeasurements);
        grupos.put("INDEX", indexMeasurements);

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            out.println("operacion,registros,hilos,tiempo_ms,speedup,eficiencia,registros_por_segundo");
            for (Map.Entry<String, List<SpeedupMetrics.Measurement>> grupo : grupos.entrySet()) {
                List<SpeedupMetrics.Measurement> items = grupo.getValue();
                long baseline = items.isEmpty() ? 0 : items.get(0).getElapsedMillis();
                for (SpeedupMetrics.Measurement m : items) {
                    out.printf("%s,%d,%d,%d,%s,%s,%s%n",
                            grupo.getKey(), m.getRecordsProcessed(), m.getThreads(), m.getElapsedMillis(),
                            csvDouble(SpeedupMetrics.speedup(baseline, m.getElapsedMillis())),
                            csvDouble(SpeedupMetrics.efficiency(baseline, m.getElapsedMillis(), m.getThreads())),
                            csvDouble(m.throughput()));
                }
            }
        }
    }

    private static String csvDouble(double value) {
        return Double.isNaN(value) ? "" : String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    public List<SpeedupMetrics.Measurement> getPackMeasurements() { return List.copyOf(packMeasurements); }
    public List<SpeedupMetrics.Measurement> getIndexMeasurements() { return List.copyOf(indexMeasurements); }

    /**
     * ParallelIndexEngine solo necesita el archivo activo y las columnas indexables.
     * Evita cargar el mapa de offsets de millones de registros de RafStorageEngine.
     */
    private static final class DatasetView implements StorageEngine {
        private final Metadata metadata = new Metadata();

        DatasetView(Path dataset) {
            metadata.setActiveDataFile(dataset.toAbsolutePath().normalize().toString());
        }

        @Override public Metadata getMetadata() { return metadata; }
        @Override public void saveMetadata() { }
        @Override public void close() { }

        @Override public long insert(InsurancePolicy record) {
            throw new UnsupportedOperationException("Vista de solo lectura del benchmark");
        }
        @Override public InsurancePolicy readAt(long offset) {
            throw new UnsupportedOperationException("Vista de solo lectura del benchmark");
        }
        @Override public boolean markAsDeleted(long offset) {
            throw new UnsupportedOperationException("Vista de solo lectura del benchmark");
        }
        @Override public long getFileSize() throws IOException {
            return Files.size(Path.of(metadata.getActiveDataFile()));
        }
    }
}
