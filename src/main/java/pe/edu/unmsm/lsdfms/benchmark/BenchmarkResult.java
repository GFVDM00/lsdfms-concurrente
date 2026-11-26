package pe.edu.unmsm.lsdfms.benchmark;

import java.util.Locale;

/** Resultado de una configuracion de benchmark. */
public final class BenchmarkResult {

    private final String operation;
    private final long records;
    private final long activeRecords;
    private final long deletedRecords;
    private final int threads;
    private final double durationMs;
    private double speedup;
    private double efficiency;

    public BenchmarkResult(String operation, long records, long activeRecords,
                           long deletedRecords, int threads, double durationMs) {
        this.operation = operation;
        this.records = records;
        this.activeRecords = activeRecords;
        this.deletedRecords = deletedRecords;
        this.threads = threads;
        this.durationMs = durationMs;
    }

    public String getOperation() { return operation; }
    public long getRecords() { return records; }
    public long getActiveRecords() { return activeRecords; }
    public long getDeletedRecords() { return deletedRecords; }
    public int getThreads() { return threads; }
    public double getDurationMs() { return durationMs; }
    public double getSpeedup() { return speedup; }
    public double getEfficiency() { return efficiency; }

    void setMetrics(double speedup, double efficiency) {
        this.speedup = speedup;
        this.efficiency = efficiency;
    }

    public String toCsvRow() {
        return String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%.3f,%.4f,%.4f",
                operation, records, activeRecords, deletedRecords, threads,
                durationMs, speedup, efficiency);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "%-5s hilos=%d tiempo=%.2f ms speedup=%.3f eficiencia=%.3f",
                operation, threads, durationMs, speedup, efficiency);
    }
}
