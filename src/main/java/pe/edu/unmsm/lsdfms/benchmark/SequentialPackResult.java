package pe.edu.unmsm.lsdfms.benchmark;

import java.nio.file.Path;

/** Resultado de la version secuencial de PACK usada como linea base. */
public final class SequentialPackResult {

    private final long recordsRead;
    private final long activeRecords;
    private final long deletedRecords;
    private final long originalSize;
    private final long finalSize;
    private final long elapsedMillis;
    private final Path source;
    private final Path output;

    public SequentialPackResult(long recordsRead, long activeRecords, long deletedRecords,
                                long originalSize, long finalSize, long elapsedMillis,
                                Path source, Path output) {
        this.recordsRead = recordsRead;
        this.activeRecords = activeRecords;
        this.deletedRecords = deletedRecords;
        this.originalSize = originalSize;
        this.finalSize = finalSize;
        this.elapsedMillis = elapsedMillis;
        this.source = source;
        this.output = output;
    }

    public long getRecordsRead() { return recordsRead; }
    public long getActiveRecords() { return activeRecords; }
    public long getDeletedRecords() { return deletedRecords; }
    public long getOriginalSize() { return originalSize; }
    public long getFinalSize() { return finalSize; }
    public long getElapsedMillis() { return elapsedMillis; }
    public Path getSource() { return source; }
    public Path getOutput() { return output; }
}
