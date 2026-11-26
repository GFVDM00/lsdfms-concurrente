package pe.edu.unmsm.lsdfms.benchmark;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.MetadataRepository;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * StorageEngine de solo lectura para benchmarks de INDEX. Solo satisface las
 * consultas de metadata que usa {@code ParallelIndexEngine}; no participa en
 * inserciones ni borrados.
 */
final class BenchmarkStorage implements StorageEngine {

    private final Path dataFile;
    private final Metadata metadata;

    BenchmarkStorage(Path dataFile, Path metadataFile, List<String> indexedColumns)
            throws IOException {
        this.dataFile = dataFile.toAbsolutePath().normalize();
        Metadata loaded;
        if (metadataFile != null && Files.isRegularFile(metadataFile)) {
            loaded = new MetadataRepository(metadataFile.toFile()).load();
        } else {
            loaded = new Metadata();
        }
        loaded.setActiveDataFile(this.dataFile.toString());
        loaded.setIndexedColumns(indexedColumns);
        this.metadata = loaded;
    }

    @Override
    public long insert(InsurancePolicy record) {
        throw new UnsupportedOperationException("BenchmarkStorage es de solo lectura");
    }

    @Override
    public InsurancePolicy readAt(long offset) {
        throw new UnsupportedOperationException("BenchmarkStorage es de solo lectura");
    }

    @Override
    public boolean markAsDeleted(long offset) {
        throw new UnsupportedOperationException("BenchmarkStorage es de solo lectura");
    }

    @Override
    public long getFileSize() throws IOException {
        return Files.size(dataFile);
    }

    @Override
    public Metadata getMetadata() {
        return metadata;
    }

    @Override
    public void saveMetadata() {
        // No hay metadata propia que persistir durante el benchmark.
    }

    @Override
    public void close() {
        // Sin recursos abiertos.
    }
}
