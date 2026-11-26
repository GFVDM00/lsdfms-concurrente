package pe.edu.unmsm.lsdfms.pack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable summary of one PACK run. */
public final class PackResult {
    private final long originalRecords, activeRecords, deletedRecords, originalSize, finalSize, elapsedMillis;
    private final int threadsUsed;
    private final Path source, output;
    private final List<Path> generatedFiles;
    PackResult(long originalRecords, long activeRecords, long deletedRecords, long originalSize, long finalSize,
               long elapsedMillis, int threadsUsed, Path source, Path output) {
        this.originalRecords = originalRecords; this.activeRecords = activeRecords; this.deletedRecords = deletedRecords;
        this.originalSize = originalSize; this.finalSize = finalSize; this.elapsedMillis = elapsedMillis;
        this.threadsUsed = threadsUsed; this.source = source; this.output = output;
        this.generatedFiles = Collections.unmodifiableList(new ArrayList<>(Collections.singletonList(output)));
    }
    public long getOriginalRecords() { return originalRecords; }
    public long getActiveRecords() { return activeRecords; }
    public long getDeletedRecords() { return deletedRecords; }
    public long getOriginalSize() { return originalSize; }
    public long getFinalSize() { return finalSize; }
    public long getElapsedMillis() { return elapsedMillis; }
    public int getThreadsUsed() { return threadsUsed; }
    public Path getSource() { return source; }
    public Path getOutput() { return output; }
    public List<Path> getGeneratedFiles() { return generatedFiles; }
    public long getBytesSaved() { return originalSize - finalSize; }
    public double getReductionPercent() { return originalSize == 0 ? 0.0 : 100.0 * getBytesSaved() / originalSize; }
}
