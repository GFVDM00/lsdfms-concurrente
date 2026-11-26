package pe.edu.unmsm.lsdfms.generator;

import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.RafStorageEngine;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Prueba ejecutable con -ea y sin dependencias externas. */
public final class MassiveDataGeneratorTest {

    private MassiveDataGeneratorTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("generator-test-");
        try {
            testGenerateAndVerify(root);
            testReproducibility(root);
            testDeletedPercentage(root);
            System.out.println("All MassiveDataGenerator tests passed.");
        } finally {
            deleteTree(root);
        }
    }

    private static void testGenerateAndVerify(Path root) throws Exception {
        Path data = root.resolve("dataset.dat");
        Path metadata = root.resolve("dataset.properties");
        GenerationResult result = MassiveDataGenerator.generate(
                new DatasetSpec(5_000, data, metadata, 123, 10));

        check(result.getRecords() == 5_000, "registros generados");
        check(result.getActiveRecords() + result.getDeletedRecords() == 5_000,
                "conteos activos + eliminados");
        check(Files.size(data) == result.getBytesWritten(), "tamano del archivo");

        DatasetVerifier.requireValid(data, metadata);
        try (RafStorageEngine storage = new RafStorageEngine(data.toFile(), metadata.toFile())) {
            Metadata meta = storage.getMetadata();
            check(meta.getTotalRecords() == 5_000, "metadata total");
            check(meta.getActiveRecords() == result.getActiveRecords(), "metadata activos");
            check(meta.getDeletedRecords() == result.getDeletedRecords(), "metadata eliminados");
            check(storage.readAt(0).getId() == 1L, "primer registro");
        }

        Set<Integer> lengths = recordLengths(data);
        check(lengths.size() > 1, "el dataset debe tener longitudes variables");
    }

    private static void testReproducibility(Path root) throws Exception {
        Path dataA = root.resolve("repro-a.dat");
        Path metaA = root.resolve("repro-a.properties");
        Path dataB = root.resolve("repro-b.dat");
        Path metaB = root.resolve("repro-b.properties");
        DatasetSpec specA = new DatasetSpec(2_000, dataA, metaA, 55, 7);
        DatasetSpec specB = new DatasetSpec(2_000, dataB, metaB, 55, 7);
        MassiveDataGenerator.generate(specA);
        MassiveDataGenerator.generate(specB);
        check(sha256(dataA).equals(sha256(dataB)), "mismo seed debe producir el mismo .dat");
    }

    private static void testDeletedPercentage(Path root) throws Exception {
        Path data = root.resolve("deleted.dat");
        Path metadata = root.resolve("deleted.properties");
        GenerationResult result = MassiveDataGenerator.generate(
                new DatasetSpec(2_000, data, metadata, 9, 25));
        check(result.getDeletedRecords() > 0, "debe haber registros eliminados");
        check(result.getDeletedRecords() < result.getRecords(), "no todos deben estar eliminados");
        DatasetVerifier.requireValid(data, metadata);
    }

    private static Set<Integer> recordLengths(Path data) throws Exception {
        Set<Integer> lengths = new HashSet<>();
        try (RandomAccessFile file = new RandomAccessFile(data.toFile(), "r")) {
            long length = file.length();
            long offset = 0;
            while (offset < length) {
                file.seek(offset);
                file.readUnsignedByte();
                int recordLength = file.readInt();
                lengths.add(recordLength);
                offset += 5L + recordLength;
            }
        }
        return lengths;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder text = new StringBuilder();
        for (byte value : hash) {
            text.append(String.format("%02x", value));
        }
        return text.toString();
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
