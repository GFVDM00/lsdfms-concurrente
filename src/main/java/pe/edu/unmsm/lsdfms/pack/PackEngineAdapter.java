package pe.edu.unmsm.lsdfms.pack;

import pe.edu.unmsm.lsdfms.storage.MutableStorageEngine;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adaptador entre {@link ParallelPackEngine} y el contrato {@link PackEngine}
 * del servidor.
 *
 * <p>Compacta el archivo activo a un temporal, publica el resultado mediante
 * {@link MutableStorageEngine#replaceActiveDataFile(Path)} y deja la Metadata
 * marcada como no sincronizada para que INDEX() se ejecute a continuacion.</p>
 */
public final class PackEngineAdapter implements PackEngine {

    @Override
    public long pack(StorageEngine storage, int numThreads) throws IOException, InterruptedException {
        if (!(storage instanceof MutableStorageEngine mutable)) {
            throw new IOException("El motor de almacenamiento no soporta reemplazo de archivo activo");
        }
        Path source = mutable.activeDataFilePath();
        Path temporary = source.resolveSibling(source.getFileName() + ".packed-"
                + System.nanoTime() + ".tmp");
        try {
            PackResult result = new ParallelPackEngine().pack(source, temporary, numThreads);
            mutable.replaceActiveDataFile(temporary);
            return result.getActiveRecords();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
