package pe.edu.unmsm.lsdfms.pack;

import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Conecta ParallelPackEngine con el contrato PackEngine que consume QueueDispatcher.
 * ParallelPackEngine nunca reemplaza su archivo de origen, asi que este adaptador
 * anade el paso de publicacion; si algo falla, el archivo activo queda intacto.
 */
public final class StoragePackAdapter implements PackEngine {

    private final ParallelPackEngine engine;

    public StoragePackAdapter() {
        this(new ParallelPackEngine());
    }

    public StoragePackAdapter(ParallelPackEngine engine) {
        this.engine = engine;
    }

    @Override
    public long pack(StorageEngine storage, int numThreads) throws IOException, InterruptedException {
        Path source = Path.of(storage.getMetadata().getActiveDataFile())
                .toAbsolutePath().normalize();

        if (!Files.isRegularFile(source) || Files.size(source) == 0) {
            return 0;
        }

        Path output = source.resolveSibling(source.getFileName() + ".packed");
        Files.deleteIfExists(output);

        boolean published = false;
        try {
            PackResult result = engine.pack(source, output, numThreads);
            storage.replaceDataFile(result.getOutput());
            published = true;
            return result.getActiveRecords();
        } finally {
            if (!published) {
                Files.deleteIfExists(output);
            }
        }
    }
}
