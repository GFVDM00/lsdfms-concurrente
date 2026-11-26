package pe.edu.unmsm.lsdfms.pack;

import pe.edu.unmsm.lsdfms.generator.DatasetSpec;
import pe.edu.unmsm.lsdfms.generator.DatasetVerifier;
import pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator;
import pe.edu.unmsm.lsdfms.index.ParallelIndexEngine;
import pe.edu.unmsm.lsdfms.storage.RafStorageEngine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Verifica la publicacion coordinada de PACK sobre el archivo activo. */
public final class PackEngineAdapterTest {

    private PackEngineAdapterTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pack-adapter-test-");
        try {
            Path data = root.resolve("dataset.dat");
            Path metadata = root.resolve("dataset.properties");
            MassiveDataGenerator.generate(new DatasetSpec(2_000, data, metadata, 77, 15));

            long originalSize;
            long active;
            try (RafStorageEngine storage = new RafStorageEngine(data.toFile(), metadata.toFile())) {
                originalSize = storage.getFileSize();
                active = storage.getMetadata().getActiveRecords();
                long kept = new PackEngineAdapter().pack(storage, 4);
                check(kept == active, "PACK debe conservar los activos");
                check(storage.getMetadata().getTotalRecords() == active, "total tras PACK");
                check(storage.getMetadata().getActiveRecords() == active, "activos tras PACK");
                check(storage.getMetadata().getDeletedRecords() == 0, "eliminados tras PACK");
                check(!storage.getMetadata().isIndexSynced(), "PACK debe invalidar indices");
                check(storage.getMetadata().getLastCompactedAt() > 0, "lastCompactedAt");
                check(storage.getFileSize() < originalSize, "el archivo debe reducirse");
                var first = storage.readAt(0);
                check(!first.isDeleted() && first.getId() > 0, "lectura tras reemplazo");

                DatasetVerifier.requireValid(data, metadata);

                ParallelIndexEngine index = new ParallelIndexEngine(root, 1);
                index.rebuildAllIndexes(storage);
                storage.getMetadata().markIndexesSynchronized();
                storage.saveMetadata();
                check(index.searchOffset("id", first.getId()) == 0L,
                        "busqueda id tras PACK+INDEX");
            }
            System.out.println("All PackEngineAdapter tests passed.");
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
