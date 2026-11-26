package pe.edu.unmsm.lsdfms.benchmark;

/**
 * Metricas de rendimiento paralelo: S(p) = T(1)/T(p) y E(p) = S(p)/p.
 *
 * T(1) es el mismo algoritmo con un unico hilo de trabajo, no una implementacion
 * distinta, de modo que la comparacion aisla el efecto del paralelismo.
 */
public final class SpeedupMetrics {

    public static final class Measurement {
        private final String operation;
        private final int threads;
        private final long elapsedMillis;
        private final long recordsProcessed;

        public Measurement(String operation, int threads, long elapsedMillis, long recordsProcessed) {
            if (threads < 1) throw new IllegalArgumentException("threads debe ser >= 1");
            if (elapsedMillis < 0) throw new IllegalArgumentException("elapsedMillis no puede ser negativo");
            this.operation = operation;
            this.threads = threads;
            this.elapsedMillis = elapsedMillis;
            this.recordsProcessed = recordsProcessed;
        }

        public String getOperation() { return operation; }
        public int getThreads() { return threads; }
        public long getElapsedMillis() { return elapsedMillis; }
        public long getRecordsProcessed() { return recordsProcessed; }

        public double throughput() {
            return elapsedMillis <= 0 ? Double.NaN : recordsProcessed * 1000.0 / elapsedMillis;
        }
    }

    private SpeedupMetrics() {}

    public static double speedup(long baselineMillis, long parallelMillis) {
        if (baselineMillis <= 0 || parallelMillis <= 0) return Double.NaN;
        return (double) baselineMillis / parallelMillis;
    }

    /** En tanto por uno. */
    public static double efficiency(long baselineMillis, long parallelMillis, int threads) {
        double s = speedup(baselineMillis, parallelMillis);
        if (Double.isNaN(s) || threads < 1) return Double.NaN;
        return s / threads;
    }

    /** Fraccion paralelizable segun Amdahl: f = (1 - 1/S) / (1 - 1/p). */
    public static double amdahlParallelFraction(double observedSpeedup, int threads) {
        if (Double.isNaN(observedSpeedup) || threads <= 1 || observedSpeedup <= 0) return Double.NaN;
        return (1.0 - 1.0 / observedSpeedup) / (1.0 - 1.0 / threads);
    }

    public static String formatDouble(double value) {
        return Double.isNaN(value) ? "n/d" : String.format("%.2f", value);
    }

    public static String formatPercent(double ratio) {
        return Double.isNaN(ratio) ? "n/d" : String.format("%.1f%%", ratio * 100);
    }
}
