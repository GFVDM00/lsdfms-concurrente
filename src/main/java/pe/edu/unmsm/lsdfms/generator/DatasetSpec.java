package pe.edu.unmsm.lsdfms.generator;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Especificacion inmutable de un dataset sintetico LSDFMS.
 *
 * <p>El generador escribe el archivo binario directamente y guarda la metadata
 * una sola vez al final. Los paths se normalizan a absolutos para que la
 * metadata pueda ser consumida por {@code RafStorageEngine} y por el motor de
 * indices sin depender del directorio de trabajo.</p>
 */
public final class DatasetSpec {

    private final long records;
    private final Path output;
    private final Path metadata;
    private final long seed;
    private final int deletedPercent;

    public DatasetSpec(long records, Path output, Path metadata, long seed, int deletedPercent) {
        if (records <= 0) {
            throw new IllegalArgumentException("records debe ser mayor que cero");
        }
        if (deletedPercent < 0 || deletedPercent > 100) {
            throw new IllegalArgumentException("deletedPercent debe estar entre 0 y 100");
        }
        this.records = records;
        this.output = Objects.requireNonNull(output, "output no puede ser null")
                .toAbsolutePath().normalize();
        this.metadata = Objects.requireNonNull(metadata, "metadata no puede ser null")
                .toAbsolutePath().normalize();
        if (this.output.equals(this.metadata)) {
            throw new IllegalArgumentException("output y metadata deben ser archivos distintos");
        }
        this.seed = seed;
        this.deletedPercent = deletedPercent;
    }

    public long records() { return records; }
    public Path output() { return output; }
    public Path metadata() { return metadata; }
    public long seed() { return seed; }
    public int deletedPercent() { return deletedPercent; }
}
