package pe.edu.unmsm.lsdfms.generator;

import java.nio.file.Path;

/** Resumen de una ejecucion del generador masivo. */
public final class GenerationResult {

    private final Path dataFile;
    private final Path metadataFile;
    private final long records;
    private final long activeRecords;
    private final long deletedRecords;
    private final long bytesWritten;
    private final long elapsedMillis;

    public GenerationResult(Path dataFile, Path metadataFile, long records,
                            long activeRecords, long deletedRecords,
                            long bytesWritten, long elapsedMillis) {
        this.dataFile = dataFile;
        this.metadataFile = metadataFile;
        this.records = records;
        this.activeRecords = activeRecords;
        this.deletedRecords = deletedRecords;
        this.bytesWritten = bytesWritten;
        this.elapsedMillis = elapsedMillis;
    }

    public Path getDataFile() { return dataFile; }
    public Path getMetadataFile() { return metadataFile; }
    public long getRecords() { return records; }
    public long getActiveRecords() { return activeRecords; }
    public long getDeletedRecords() { return deletedRecords; }
    public long getBytesWritten() { return bytesWritten; }
    public long getElapsedMillis() { return elapsedMillis; }

    public double getRecordsPerSecond() {
        return elapsedMillis == 0 ? 0.0 : records * 1000.0 / elapsedMillis;
    }

    @Override
    public String toString() {
        return "GenerationResult{" +
                "dataFile=" + dataFile +
                ", records=" + records +
                ", activeRecords=" + activeRecords +
                ", deletedRecords=" + deletedRecords +
                ", bytesWritten=" + bytesWritten +
                ", elapsedMillis=" + elapsedMillis +
                '}';
    }
}
