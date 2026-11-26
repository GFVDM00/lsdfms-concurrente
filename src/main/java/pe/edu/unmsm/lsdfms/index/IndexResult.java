package pe.edu.unmsm.lsdfms.index;

import java.nio.file.Path;
import java.util.List;

/** Metricas de la ultima reconstruccion, para el modulo de benchmarking. */
public final class IndexResult {
    private final List<String> columns;
    private final long recordsRead;
    private final long recordsIndexed;
    private final long recordsDeleted;
    private final long durationMs;
    private final int threads;
    private final List<Path> outputs;
    private final Path source;

    public IndexResult(List<String> columns, long recordsRead, long recordsIndexed,
                       long recordsDeleted, long durationMs, int threads,
                       List<Path> outputs, Path source) {
        this.columns = List.copyOf(columns);
        this.recordsRead = recordsRead;
        this.recordsIndexed = recordsIndexed;
        this.recordsDeleted = recordsDeleted;
        this.durationMs = durationMs;
        this.threads = threads;
        this.outputs = List.copyOf(outputs);
        this.source = source;
    }

    public List<String> getColumns() { return columns; }
    public long getRecordsRead() { return recordsRead; }
    public long getRecordsIndexed() { return recordsIndexed; }
    public long getRecordsDeleted() { return recordsDeleted; }
    public long getDurationMs() { return durationMs; }
    public int getThreads() { return threads; }
    public List<Path> getOutputs() { return outputs; }
    public Path getSource() { return source; }
}
