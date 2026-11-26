package pe.edu.unmsm.lsdfms.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Calculo de Speedup/eficiencia y escritura del CSV de resultados. */
public final class SpeedupMetrics {

    private SpeedupMetrics() {}

    /**
     * Aplica T1 como linea base para cada operacion. Si no se ejecuto T1,
     * speedup y eficiencia quedan en cero y el reporte lo indica.
     */
    public static void apply(List<BenchmarkResult> results) {
        for (BenchmarkResult result : results) {
            BenchmarkResult baseline = results.stream()
                    .filter(candidate -> candidate.getOperation().equals(result.getOperation()))
                    .filter(candidate -> candidate.getThreads() == 1)
                    .findFirst()
                    .orElse(null);
            if (baseline == null || baseline.getDurationMs() <= 0) {
                result.setMetrics(0.0, 0.0);
            } else if (result.getThreads() == 1) {
                result.setMetrics(1.0, 1.0);
            } else {
                double speedup = baseline.getDurationMs() / result.getDurationMs();
                result.setMetrics(speedup, speedup / result.getThreads());
            }
        }
    }

    public static void writeCsv(Path output, List<BenchmarkResult> results) throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder csv = new StringBuilder();
        csv.append("operacion,registros,activos,eliminados,hilos,tiempo_ms,speedup,eficiencia\n");
        for (BenchmarkResult result : results) {
            csv.append(result.toCsvRow()).append('\n');
        }
        Files.writeString(output, csv.toString(), StandardCharsets.UTF_8);
    }

    public static void printSummary(List<BenchmarkResult> results) {
        System.out.printf("%-6s %7s %12s %10s %12s%n",
                "OP", "HILOS", "TIEMPO(ms)", "SPEEDUP", "EFICIENCIA");
        for (BenchmarkResult result : results) {
            System.out.printf("%-6s %7d %12.2f %10s %12s%n",
                    result.getOperation(),
                    result.getThreads(),
                    result.getDurationMs(),
                    result.getSpeedup() > 0 ? String.format("%.3f", result.getSpeedup()) : "n/a",
                    result.getEfficiency() > 0 ? String.format("%.3f", result.getEfficiency()) : "n/a");
        }
    }
}
