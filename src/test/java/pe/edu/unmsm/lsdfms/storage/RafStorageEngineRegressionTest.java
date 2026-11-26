package pe.edu.unmsm.lsdfms.storage;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ejecutable sin framework: javac ... && java ...RafStorageEngineRegressionTest
 */
public final class RafStorageEngineRegressionTest {
    private RafStorageEngineRegressionTest() {}

    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("raf-storage-regression").toFile();
        File dataFile = new File(directory, "policies.dat");
        File metadataFile = new File(directory, "metadata.properties");
        try {
            regressionAndPersistence(dataFile, metadataFile);
            reconstructMetadata(dataFile, metadataFile);
            concurrentInserts(dataFile, metadataFile);
            staleAndIncompleteMetadata(dataFile, metadataFile, directory);
            rollbackOnMetadataFailure(directory);
            auditReproductions(directory);
            rejectsTruncatedFile(directory);
            rejectsImpossiblePayload(directory);
            System.out.println("RafStorageEngineRegressionTest: OK");
        } finally {
            Files.deleteIfExists(dataFile.toPath());
            Files.deleteIfExists(metadataFile.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private static void auditReproductions(File directory) throws Exception {
        File data = new File(directory, "audit.dat");
        File metadata = new File(directory, "audit.properties");
        try (RafStorageEngine engine = new RafStorageEngine(data, metadata)) {
            engine.insert(policy(1));
            engine.getMetadata().markIndexesSynchronized();
            engine.saveMetadata();
        }
        java.util.Properties properties = new java.util.Properties();
        try (java.io.InputStream input = Files.newInputStream(metadata.toPath())) {
            properties.load(input);
        }
        properties.setProperty("indexedColumns", ", ,");
        try (java.io.OutputStream output = Files.newOutputStream(metadata.toPath())) {
            properties.store(output, "");
        }
        try (RafStorageEngine reopened = new RafStorageEngine(data, metadata)) {
            check(reopened.getMetadata().getIndexedColumns().isEmpty(),
                    "separadores producen lista de columnas vacía");
            check(!reopened.getMetadata().isIndexSynced(),
                    "columnas vacías no certifican índices sincronizados");
        }

        try (RafStorageEngine engine = new RafStorageEngine(data, metadata)) {
            engine.insert(policy(2));
            Metadata staleView = engine.getMetadata();
            Thread writer = new Thread(() -> {
                engine.getMetadata().markIndexesSynchronized();
                try {
                    engine.saveMetadata();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            writer.start();
            writer.join();
            engine.saveMetadata(); // Guarda la vista antigua, sin intención de cambiar el flag.
            check(engine.getMetadata().isIndexSynced(),
                    "guardar una vista de solo lectura no revierte el cambio concurrente");
            check(staleView.getTotalRecords() == 2, "la vista capturada sigue siendo independiente");
        }

        File rollbackData = new File(directory, "audit-rollback.dat");
        File rollbackMetadata = new File(directory, "audit-rollback.properties");
        try (RafStorageEngine engine = new RafStorageEngine(rollbackData, rollbackMetadata)) {
            engine.insert(policy(3));
            Files.delete(rollbackMetadata.toPath());
            Files.createDirectory(rollbackMetadata.toPath());
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicBoolean observedUncommitted = new AtomicBoolean();
            Thread reader = new Thread(() -> {
                while (running.get()) {
                    Metadata status = engine.getMetadata();
                    if (status.getTotalRecords() > 1 || status.getActiveRecords() != 1
                            || status.getDeletedRecords() != 0) {
                        observedUncommitted.set(true);
                    }
                }
            });
            reader.start();
            for (int i = 0; i < 30; i++) {
                expectIOException(() -> engine.insert(policy(4)),
                        "insert fallido de auditoría debe reportar IOException");
            }
            for (int i = 0; i < 30; i++) {
                expectIOException(() -> engine.markAsDeleted(0),
                        "borrado fallido de auditoría debe reportar IOException");
            }
            running.set(false);
            reader.join();
            check(!observedUncommitted.get(),
                    "lectores no observan contadores de inserción revertidos");
            check(engine.getMetadata().getTotalRecords() == 1,
                    "contador final conserva solo inserciones confirmadas");
            Files.delete(rollbackMetadata.toPath());
        } finally {
            Files.deleteIfExists(rollbackMetadata.toPath());
            Files.deleteIfExists(rollbackData.toPath());
        }
        Files.deleteIfExists(data.toPath());
        Files.deleteIfExists(metadata.toPath());
    }

    private static void regressionAndPersistence(File dataFile, File metadataFile)
            throws Exception {
        long validOffset;
        try (RafStorageEngine engine = new RafStorageEngine(dataFile, metadataFile)) {
            validOffset = engine.insert(policy(1));
            long size = engine.getFileSize();
            long records = engine.getMetadata().getTotalRecords();
            long active = engine.getMetadata().getActiveRecords();
            long deleted = engine.getMetadata().getDeletedRecords();

            check(validOffset == 0, "primera cabecera en offset 0");
            check(!engine.markAsDeleted(1), "offset interior rechazado al borrar");
            check(engine.getFileSize() == size, "borrado inválido no cambia el tamaño");
            check(engine.getMetadata().getTotalRecords() == records, "total intacto");
            check(engine.getMetadata().getActiveRecords() == active, "activos intactos");
            check(engine.getMetadata().getDeletedRecords() == deleted, "borrados intactos");
            check(!engine.readAt(validOffset).isDeleted(), "cabecera intacta tras borrado inválido");
            expectIOException(() -> engine.readAt(1), "lectura en offset interior rechazada");

            long secondOffset = engine.insert(policy(2));
            check(engine.readAt(secondOffset).getId() == 2, "lectura directa por offset");
            check(engine.markAsDeleted(validOffset), "primer borrado efectivo");
            check(!engine.markAsDeleted(validOffset), "borrado repetido no cuenta de nuevo");
            check(engine.getMetadata().getDeletedRecords() == 1, "contador borrados exacto");
            check(engine.getMetadata().getLastOffset() == engine.getFileSize(),
                    "lastOffset es EOF");
        }

        try (RafStorageEngine reopened = new RafStorageEngine(dataFile, metadataFile)) {
            check(reopened.getMetadata().getTotalRecords() == 2, "persistencia total");
            check(reopened.getMetadata().getActiveRecords() == 1, "persistencia activos");
            check(reopened.getMetadata().getDeletedRecords() == 1, "persistencia borrados");
            check(reopened.readAt(validOffset).isDeleted(), "bandera persiste");
            check(!reopened.markAsDeleted(1), "offset inválido tras reapertura");
            check(!reopened.markAsDeleted(validOffset), "borrado repetido tras reapertura");
        }
    }

    private static void reconstructMetadata(File dataFile, File metadataFile) throws Exception {
        Files.delete(metadataFile.toPath());
        try (RafStorageEngine engine = new RafStorageEngine(dataFile, metadataFile)) {
            check(engine.getMetadata().getTotalRecords() == 2, "reconstruye total sin metadata");
            check(engine.getMetadata().getActiveRecords() == 1, "reconstruye activos");
            check(engine.getMetadata().getDeletedRecords() == 1, "reconstruye borrados");
            check(engine.getMetadata().getLastOffset() == engine.getFileSize(),
                    "reconstruye EOF");
        }
    }

    private static void concurrentInserts(File dataFile, File metadataFile) throws Exception {
        try (RafStorageEngine engine = new RafStorageEngine(dataFile, metadataFile)) {
            int workers = 4;
            int each = 25;
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
            Thread statusReader = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10_000; i++) {
                        Metadata status = engine.getMetadata();
                        check(status.getTotalRecords()
                                        == status.getActiveRecords() + status.getDeletedRecords(),
                                "lectura conjunta de estado coherente");
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            statusReader.start();
            for (int worker = 0; worker < workers; worker++) {
                final int idBase = worker * each + 100;
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < each; i++) {
                            long offset = engine.insert(policy(idBase + i));
                            check(engine.readAt(offset).getId() == idBase + i,
                                    "insert/read concurrente");
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            statusReader.join();
            check(failures.isEmpty(), "sin fallos concurrentes: " + failures);
            check(engine.getMetadata().getTotalRecords() == 102, "conteo concurrente exacto");
            check(engine.getMetadata().getLastOffset() == engine.getFileSize(),
                    "EOF tras inserciones concurrentes");
        }
    }

    private static void staleAndIncompleteMetadata(
            File dataFile, File metadataFile, File directory) throws Exception {
        File oldMetadata = new File(directory, "old-metadata.properties");
        try (RafStorageEngine engine = new RafStorageEngine(dataFile, metadataFile)) {
            engine.getMetadata().markIndexesSynchronized();
            engine.saveMetadata();
            Files.copy(metadataFile.toPath(), oldMetadata.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            engine.insert(policy(9000));
        }
        Files.copy(oldMetadata.toPath(), metadataFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        try (RafStorageEngine reopened = new RafStorageEngine(dataFile, metadataFile)) {
            check(reopened.getMetadata().getTotalRecords() == 103,
                    "reconstruye conteo ante metadata vieja");
            check(!reopened.getMetadata().isIndexSynced(),
                    "metadata vieja no certifica índices sincronizados");
        }

        Files.writeString(metadataFile.toPath(), "");
        try (RafStorageEngine reopened = new RafStorageEngine(dataFile, metadataFile)) {
            check(!reopened.getMetadata().isIndexSynced(),
                    "properties vacío no certifica índices sincronizados");
        }
        Files.writeString(metadataFile.toPath(), "isIndexSynced=true\n");
        try (RafStorageEngine reopened = new RafStorageEngine(dataFile, metadataFile)) {
            check(!reopened.getMetadata().isIndexSynced(),
                    "properties incompleto no certifica índices sincronizados");
        }
        try (RafStorageEngine engine = new RafStorageEngine(dataFile, metadataFile)) {
            engine.getMetadata().markIndexesSynchronized();
            check(engine.getMetadata().isIndexSynced(),
                    "marca hecha sobre la vista se refleja en el motor");
        }
        try (RafStorageEngine reopened = new RafStorageEngine(dataFile, metadataFile)) {
            check(reopened.getMetadata().isIndexSynced(),
                    "marca sobre vista persiste al cerrar sin save explícito");
        }
        Files.deleteIfExists(oldMetadata.toPath());
    }

    private static void rollbackOnMetadataFailure(File directory) throws Exception {
        File data = new File(directory, "rollback.dat");
        File metadata = new File(directory, "rollback.properties");
        long offset;
        try (RafStorageEngine engine = new RafStorageEngine(data, metadata)) {
            offset = engine.insert(policy(1));
        }
        byte[] beforeFailure = Files.readAllBytes(data.toPath());
        try (RafStorageEngine engine = new RafStorageEngine(data, metadata)) {
            // Restaurar el archivo-directory después de abrir evita que falle la reconstrucción.
            Files.delete(metadata.toPath());
            Files.createDirectory(metadata.toPath());
            long size = engine.getFileSize();
            long total = engine.getMetadata().getTotalRecords();
            expectIOException(() -> engine.insert(policy(2)), "insert reporta fallo de metadata");
            check(engine.getFileSize() == size, "insert fallido revierte bytes");
            check(engine.getMetadata().getTotalRecords() == total,
                    "insert fallido revierte contadores");
            check(engine.readAt(offset).getId() == 1, "insert fallido conserva registro previo");

            expectIOException(() -> engine.markAsDeleted(offset),
                    "borrado reporta fallo de metadata");
            check(!engine.readAt(offset).isDeleted(), "borrado fallido restaura bandera");
            check(engine.getMetadata().getActiveRecords() == 1,
                    "borrado fallido revierte contadores");
            Files.delete(metadata.toPath());
            engine.saveMetadata();
        } finally {
            Files.deleteIfExists(metadata.toPath());
        }
        check(java.util.Arrays.equals(beforeFailure, Files.readAllBytes(data.toPath())),
                "datos previos intactos tras fallos");
        try (RafStorageEngine reopened = new RafStorageEngine(data, metadata)) {
            check(reopened.getMetadata().getTotalRecords() == 1,
                    "reapertura tras rollback conserva conteo");
            check(!reopened.readAt(offset).isDeleted(),
                    "reapertura tras rollback conserva registro activo");
        }
        Files.deleteIfExists(data.toPath());
        Files.deleteIfExists(metadata.toPath());
    }

    private static void rejectsTruncatedFile(File directory) throws Exception {
        File truncated = new File(directory, "truncated.dat");
        File metadata = new File(directory, "truncated.properties");
        Files.write(truncated.toPath(), new byte[] {0, 0, 0, 0, 4, 1});
        try {
            new RafStorageEngine(truncated, metadata).close();
            throw new AssertionError("debió rechazar un registro truncado");
        } catch (IOException expected) {
            // Esperado: el encabezado declara 4 bytes y solo existe 1.
        } finally {
            Files.deleteIfExists(truncated.toPath());
            Files.deleteIfExists(metadata.toPath());
        }
    }

    private static void rejectsImpossiblePayload(File directory) throws Exception {
        File impossible = new File(directory, "impossible.dat");
        File metadata = new File(directory, "impossible.properties");
        Files.write(impossible.toPath(), new byte[] {0, 0, 0, 0, 0});
        try {
            new RafStorageEngine(impossible, metadata);
            throw new AssertionError("debió rechazar payload vacío imposible");
        } catch (IOException expected) {
            // El payload válido mínimo de InsurancePolicy ocupa 26 bytes.
        } finally {
            Files.deleteIfExists(impossible.toPath());
            Files.deleteIfExists(metadata.toPath());
        }
    }

    private static InsurancePolicy policy(long id) {
        return new InsurancePolicy(id, "12345678", "Titular " + id,
                "Salud", "Lima", 100.0, "2026-01-01");
    }

    private static void expectIOException(ThrowingRunnable action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Esperado.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
