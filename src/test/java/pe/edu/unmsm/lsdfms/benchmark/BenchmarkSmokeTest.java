package pe.edu.unmsm.lsdfms.benchmark;

import pe.edu.unmsm.lsdfms.generator.DatasetSpec;
import pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Smoke test del runner de benchmarks; usa un dataset pequeno. */
public final class BenchmarkSmokeTest {

    private BenchmarkSmokeTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("benchmark-test-");
        try {
            Path data = root.resolve("dataset.dat");
            Path metadata = root.resolve("dataset.properties");
            MassiveDataGenerator.generate(new DatasetSpec(3_000, data, metadata, 321, 10));

            Path csv = root.resolve("benchmark.csv");
            BenchmarkConfig config = new BenchmarkConfig(data, metadata, csv,
                    List.of(BenchmarkConfig.Operation.PACK, BenchmarkConfig.Operation.INDEX),
                    List.of(1, 2), 1, 0);
            List<BenchmarkResult> results = BenchmarkRunner.run(config);

            check(results.size() == 4, "deben existir cuatro resultados (2 operaciones x 2 hilos)");
            for (BenchmarkResult result : results) {
                check(result.getDurationMs() > 0, "tiempo positivo para " + result);
                check(result.getRecords() == 3_000, "registros del resultado");
                if (result.getThreads() == 1) {
                    check(result.getSpeedup() == 1.0, "T1 debe tener speedup 1");
                } else {
                    check(result.getSpeedup() > 0, "T2 debe tener speedup calculado");
                }
            }
            check(Files.isRegularFile(csv), "debe existir el CSV");
            List<String> lines = Files.readAllLines(csv);
            check(lines.size() == 5, "CSV debe tener cabecera + 4 filas");
            check(lines.get(0).startsWith("operacion,registros"),
                    "cabecera CSV inesperada: " + lines.get(0));
            System.out.println("All BenchmarkSmokeTest tests passed.");
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
