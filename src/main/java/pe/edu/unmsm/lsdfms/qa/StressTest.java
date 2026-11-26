package pe.edu.unmsm.lsdfms.qa;

import pe.edu.unmsm.lsdfms.generator.DatasetSpec;
import pe.edu.unmsm.lsdfms.generator.GenerationResult;
import pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator;
import pe.edu.unmsm.lsdfms.generator.PolicyFactory;
import pe.edu.unmsm.lsdfms.index.ParallelIndexEngine;
import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.pack.PackResult;
import pe.edu.unmsm.lsdfms.pack.ParallelPackEngine;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.MetadataRepository;
import pe.edu.unmsm.lsdfms.storage.RafStorageEngine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Prueba de estres e integridad de ciclos borrado logico -> INDEX -> PACK ->
 * INDEX sobre datasets reales.
 *
 * <p>La prueba usa el motor de almacenamiento real, el motor paralelo de PACK
 * y el motor paralelo de INDEX. No depende del servidor ni del cliente web.</p>
 */
public final class StressTest {

    private StressTest() {}

    public static void main(String[] args) {
        long records = 20_000;
        int cycles = 2;
        long seed = 7;
        boolean keep = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--records" -> records = Long.parseLong(args[++i]);
                case "--cycles" -> cycles = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--keep" -> keep = true;
                case "--help", "-h" -> {
                    System.out.println("Uso: StressTest [--records N] [--cycles N] [--seed N] [--keep]");
                    return;
                }
                default -> throw new IllegalArgumentException("Opcion desconocida: " + args[i]);
            }
        }
        try {
            Path root = Files.createTempDirectory("lsdfms-stress-");
            try {
                run(records, cycles, seed, root);
            } finally {
                if (!keep) {
                    deleteTree(root);
                } else {
                    System.out.println("Directorio conservado: " + root);
                }
            }
        } catch (Exception e) {
            System.err.println("StressTest fallo: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void run(long records, int cycles, long seed, Path root) throws Exception {
        if (cycles <= 0) {
            throw new IllegalArgumentException("cycles debe ser > 0");
        }
        Files.createDirectories(root);
        Path data = root.resolve("dataset.dat");
        Path metadata = root.resolve("dataset.properties");
        GenerationResult generated = MassiveDataGenerator.generate(new DatasetSpec(
                records, data, metadata, seed, 5));
        System.out.println("Dataset inicial: " + generated);

        for (int cycle = 0; cycle < cycles; cycle++) {
            System.out.println("--- Ciclo " + (cycle + 1) + "/" + cycles + " ---");
            mutate(data, metadata, seed + cycle);
            IndexReport indexed = rebuildIndex(data, metadata, cycle);
            System.out.println("  INDEX: " + indexed.report);

            Path packed = root.resolve("packed_" + cycle + ".dat");
            Path packedMetadata = root.resolve("packed_" + cycle + ".properties");
            PackResult pack = new ParallelPackEngine().pack(data, packed, 4);
            if (pack.getActiveRecords() != indexed.report.getActive()) {
                throw new IllegalStateException("PACK conservo " + pack.getActiveRecords()
                        + " activos, se esperaban " + indexed.report.getActive());
            }
            writeMetadataForPacked(packed, packedMetadata, pack);
            IndexReport packedIndex = rebuildIndex(packed, packedMetadata, cycle);
            System.out.println("  PACK : activos=" + pack.getActiveRecords()
                    + ", eliminados=" + pack.getDeletedRecords()
                    + ", reduccion=" + String.format("%.2f", pack.getReductionPercent()) + "%");
            System.out.println("  INDEX post-PACK: " + packedIndex.report);

            data = packed;
            metadata = packedMetadata;
        }
        System.out.println("StressTest OK: " + cycles + " ciclo(s) sin errores de integridad.");
    }

    private static void mutate(Path data, Path metadata, long seed) throws Exception {
        try (RafStorageEngine storage = new RafStorageEngine(data.toFile(), metadata.toFile())) {
            List<Long> activeOffsets = activeOffsets(data);
            Collections.shuffle(activeOffsets, new Random(seed));
            int deletes = Math.max(1, activeOffsets.size() / 20); // 5%
            int deleted = 0;
            for (int i = 0; i < Math.min(deletes, activeOffsets.size()); i++) {
                if (storage.markAsDeleted(activeOffsets.get(i))) {
                    deleted++;
                }
            }

            PolicyFactory factory = new PolicyFactory(seed ^ 0xC0FFEEL);
            int inserts = Math.max(1, activeOffsets.size() / 20);
            long idBase = 1_000_000_000L + (seed % 1_000_000L) * 10_000L;
            for (int i = 0; i < inserts; i++) {
                InsurancePolicy policy = factory.next(i);
                policy.setId(idBase + i);
                storage.insert(policy);
            }
            storage.saveMetadata();
            System.out.println("  Mutacion: borrados=" + deleted + ", insertados=" + inserts);
        }
    }

    private static IndexReport rebuildIndex(Path data, Path metadata, int cycle) throws Exception {
        try (RafStorageEngine storage = new RafStorageEngine(data.toFile(), metadata.toFile())) {
            ParallelIndexEngine engine = new ParallelIndexEngine(data.getParent(), 0);
            engine.rebuildAllIndexes(storage);
            storage.getMetadata().markIndexesSynchronized();
            storage.saveMetadata();
            IntegrityReport report = IntegrityChecker.check(data, metadata, engine, true);
            report.requireValid();
            return new IndexReport(report);
        }
    }

    private static void writeMetadataForPacked(Path packed, Path metadata, PackResult result)
            throws IOException {
        Metadata meta = new Metadata();
        meta.setActiveDataFile(packed.toAbsolutePath().normalize().toString());
        meta.setTotalRecords(result.getActiveRecords());
        meta.setActiveRecords(result.getActiveRecords());
        meta.setDeletedRecords(0);
        meta.setLastOffset(result.getFinalSize());
        meta.setIndexSynced(false);
        meta.setLastCompactedAt(System.currentTimeMillis());
        meta.setLastIndexedAt(0L);
        meta.setIndexedColumns(List.of("id", "dni", "departamento", "tipoSeguro"));
        new MetadataRepository(metadata.toFile()).save(meta);
    }

    private static List<Long> activeOffsets(Path data) throws IOException {
        List<Long> offsets = new ArrayList<>();
        try (RandomAccessFile file = new RandomAccessFile(data.toFile(), "r")) {
            long length = file.length();
            long offset = 0;
            while (offset < length) {
                file.seek(offset);
                int flag = file.readUnsignedByte();
                int recordLength = file.readInt();
                long next = offset + 5L + recordLength;
                if (next > length || recordLength < 26) {
                    throw new IOException("Registro invalido en offset " + offset);
                }
                if (flag == 0x00) {
                    offsets.add(offset);
                }
                offset = next;
            }
        }
        return offsets;
    }

    private static void deleteTree(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private record IndexReport(IntegrityReport report) {}
}
