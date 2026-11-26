package pe.edu.unmsm.lsdfms.benchmark;

import pe.edu.unmsm.lsdfms.index.IndexResult;
import pe.edu.unmsm.lsdfms.index.ParallelIndexEngine;
import pe.edu.unmsm.lsdfms.pack.PackResult;
import pe.edu.unmsm.lsdfms.pack.ParallelPackEngine;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.MetadataRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Runner de benchmarks de PACK e INDEX.
 *
 * <p>Para T1 usa una implementacion secuencial de referencia. En INDEX, el
 * baseline ejecuta el mismo motor de produccion con {@code threads=1}; en PACK
 * usa {@link SequentialPackEngine}.</p>
 *
 * <p>Los benchmarks se ejecutan sobre copias en un directorio temporal para no
 * contaminar el dataset fuente ni verse afectados por el directorio de trabajo.</p>
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {}

    public static List<BenchmarkResult> run(BenchmarkConfig config) throws Exception {
        if (!Files.isRegularFile(config.dataFile())) {
            throw new IOException("No existe el dataset: " + config.dataFile());
        }
        if (!Files.isRegularFile(config.metadataFile())) {
            throw new IOException("No existe la metadata: " + config.metadataFile());
        }
        Metadata metadata = new MetadataRepository(config.metadataFile().toFile()).load();
        List<String> columns = metadata.getIndexedColumns();
        if (columns == null || columns.isEmpty()) {
            columns = List.of("id", "dni", "departamento", "tipoSeguro");
        }

        List<BenchmarkResult> results = new ArrayList<>();
        for (BenchmarkConfig.Operation operation : config.operations()) {
            switch (operation) {
                case PACK -> results.addAll(runPack(config, metadata));
                case INDEX -> results.addAll(runIndex(config, metadata, columns));
            }
        }
        SpeedupMetrics.apply(results);
        SpeedupMetrics.writeCsv(config.csvFile(), results);
        return results;
    }

    private static List<BenchmarkResult> runPack(BenchmarkConfig config, Metadata metadata)
            throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();
        long records = metadata.getTotalRecords();
        long active = metadata.getActiveRecords();
        long deleted = metadata.getDeletedRecords();

        for (int threads : config.threads()) {
            double[] samples = new double[config.repetitions()];
            for (int run = 0; run < config.warmup() + config.repetitions(); run++) {
                Path work = Files.createTempDirectory("lsdfms-bench-pack-");
                try {
                    Path source = work.resolve("source.dat");
                    Path output = work.resolve("compacted.dat");
                    Files.copy(config.dataFile(), source, StandardCopyOption.REPLACE_EXISTING);

                    long started = System.nanoTime();
                    long read;
                    long kept;
                    long discarded;
                    if (threads == 1) {
                        SequentialPackResult result = new SequentialPackEngine().pack(source, output);
                        read = result.getRecordsRead();
                        kept = result.getActiveRecords();
                        discarded = result.getDeletedRecords();
                    } else {
                        PackResult result = new ParallelPackEngine().pack(source, output, threads);
                        read = result.getOriginalRecords();
                        kept = result.getActiveRecords();
                        discarded = result.getDeletedRecords();
                    }
                    double durationMs = (System.nanoTime() - started) / 1_000_000.0;

                    if (read != records || kept != active || discarded != deleted) {
                        throw new IllegalStateException(String.format(Locale.ROOT,
                                "PACK hilos=%d contadores inesperados: read=%d kept=%d discarded=%d",
                                threads, read, kept, discarded));
                    }
                    if (Files.size(source) != Files.size(config.dataFile())) {
                        throw new IllegalStateException("El PACK modifico el archivo de origen");
                    }
                    if (!Files.isRegularFile(output)) {
                        throw new IllegalStateException("El PACK no genero la salida");
                    }
                    if (run >= config.warmup()) {
                        samples[run - config.warmup()] = durationMs;
                    }
                } finally {
                    deleteTree(work);
                }
            }
            results.add(new BenchmarkResult("PACK", records, active, deleted, threads,
                    median(samples)));
        }
        return results;
    }

    private static List<BenchmarkResult> runIndex(BenchmarkConfig config, Metadata metadata,
                                                  List<String> columns) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();
        long records = metadata.getTotalRecords();
        long active = metadata.getActiveRecords();
        long deleted = metadata.getDeletedRecords();

        for (int threads : config.threads()) {
            double[] samples = new double[config.repetitions()];
            for (int run = 0; run < config.warmup() + config.repetitions(); run++) {
                Path work = Files.createTempDirectory("lsdfms-bench-index-");
                try {
                    Path source = work.resolve("dataset.dat");
                    Files.copy(config.dataFile(), source, StandardCopyOption.REPLACE_EXISTING);

                    BenchmarkStorage storage = new BenchmarkStorage(source,
                            config.metadataFile(), columns);
                    ParallelIndexEngine engine = new ParallelIndexEngine(work, threads);
                    long started = System.nanoTime();
                    engine.rebuildAllIndexes(storage);
                    double durationMs = (System.nanoTime() - started) / 1_000_000.0;
                    validateIndex(engine.getLastResult(), records, active, deleted, threads);
                    if (run >= config.warmup()) {
                        samples[run - config.warmup()] = durationMs;
                    }
                } finally {
                    deleteTree(work);
                }
            }
            results.add(new BenchmarkResult("INDEX", records, active, deleted, threads,
                    median(samples)));
        }
        return results;
    }

    private static void validateIndex(IndexResult result, long records, long active,
                                      long deleted, int threads) {
        if (result == null) {
            throw new IllegalStateException("INDEX no produjo resultado");
        }
        if (result.getRecordsRead() != records
                || result.getRecordsIndexed() != active
                || result.getRecordsDeleted() != deleted) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "INDEX hilos=%d contadores inesperados: read=%d indexed=%d deleted=%d",
                    threads, result.getRecordsRead(), result.getRecordsIndexed(),
                    result.getRecordsDeleted()));
        }
    }

    private static double median(double[] values) {
        if (values.length == 0) {
            throw new IllegalStateException("Sin muestras de benchmark");
        }
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0
                ? (copy[middle - 1] + copy[middle]) / 2.0
                : copy[middle];
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // El benchmark no debe fallar por un temporal que el SO libere despues.
                }
            });
        } catch (IOException ignored) {
            // Limpieza best-effort.
        }
    }

    public static void main(String[] args) {
        try {
            BenchmarkConfig config = BenchmarkConfig.fromArgs(args);
            List<BenchmarkResult> results = run(config);
            Metadata metadata = new MetadataRepository(config.metadataFile().toFile()).load();
            System.out.println("=========================================================");
            System.out.println("  LSDFMS - Benchmark de PACK e INDEX");
            System.out.println("=========================================================");
            System.out.println("Dataset : " + config.dataFile());
            System.out.println("Registros: " + metadata.getTotalRecords()
                    + " (activos=" + metadata.getActiveRecords()
                    + ", eliminados=" + metadata.getDeletedRecords() + ")");
            System.out.println("Repeticiones: " + config.repetitions()
                    + " | warmup: " + config.warmup());
            System.out.println("---------------------------------------------------------");
            SpeedupMetrics.printSummary(results);
            System.out.println("---------------------------------------------------------");
            System.out.println("CSV: " + config.csvFile());
        } catch (IllegalArgumentException e) {
            System.err.println("Error de argumentos: " + e.getMessage());
            BenchmarkConfig.printHelp();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Error ejecutando benchmarks: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
