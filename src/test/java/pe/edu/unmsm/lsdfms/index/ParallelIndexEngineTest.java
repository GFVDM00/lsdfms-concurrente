package pe.edu.unmsm.lsdfms.index;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Ejecutable con -ea y sin dependencias externas. */
public final class ParallelIndexEngineTest {

    private static final List<String> COLUMNS = ColumnKeyExtractor.DEFAULT_COLUMNS;
    private static final String[] DEPARTAMENTOS = {"Lima", "Arequipa", "Cusco"};

    private ParallelIndexEngineTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("index-test-");
        try {
            testSearchAllColumns(root);
            testThreadCounts(root);
            testDuplicatesAndDeletes(root);
            testEmptyAndAllDeleted(root);
            testMalformedData(root);
            testInterruption(root);
            testCorruptIndex(root);
            testVolume(root);
            System.out.println("All ParallelIndexEngine tests passed.");
        } finally {
            removeTree(root);
        }
    }

    private static void testSearchAllColumns(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("basic"));
        Path data = writeFixture(dir, 73, true);
        List<Row> rows = readRows(data);
        List<String> allColumns = ColumnKeyExtractor.SUPPORTED_COLUMNS;
        ParallelIndexEngine engine = new ParallelIndexEngine(dir);
        engine.rebuildAllIndexes(new TestStorage(data, allColumns));

        check(engine.getIndexedColumns().equals(allColumns), "columnas indexadas");
        IndexResult result = engine.getLastResult();
        check(result.getRecordsRead() == rows.size(), "registros leidos");
        check(result.getRecordsIndexed() == countActive(rows), "registros indexados");
        check(result.getRecordsDeleted() == rows.size() - countActive(rows), "registros borrados");
        check(result.getThreads() == allColumns.size(), "un hilo por columna");

        for (Row row : rows) {
            if (!row.deleted) {
                check(engine.searchOffset("id", row.id) == row.offset, "id " + row.id);
            } else {
                check(engine.searchOffset("id", row.id) == -1, "id borrado " + row.id);
            }
        }
        for (String column : List.of("dni", "titular", "tipoSeguro", "departamento")) {
            for (Row row : rows) {
                long expected = expectedOffset(rows, column, value(row, column));
                check(engine.searchOffset(column, value(row, column)) == expected,
                        "busqueda " + column + "=" + value(row, column));
            }
        }
        check(engine.searchOffset("dni", "NO_EXISTE") == -1, "clave ausente");
        check(engine.searchOffset("fechaEmision", "2026-01-01") == -1, "columna no indexable");
        check(engine.searchOffset("id", "no-es-numero") == -1, "clave de tipo invalido");
        check(engine.searchOffset(null, "x") == -1, "columna nula");
        check(!hasWorkspace(dir), "workspace residual tras reconstruir");
    }

    private static void testThreadCounts(Path root) throws Exception {
        for (int threads : new int[]{0, 2, 4, 8}) {
            Path dir = Files.createDirectory(root.resolve("threads-" + threads));
            Path data = writeFixture(dir, 47, true);
            List<Row> rows = readRows(data);
            ParallelIndexEngine engine = threads == 0
                    ? new ParallelIndexEngine(dir) : new ParallelIndexEngine(dir, threads);
            engine.rebuildAllIndexes(new TestStorage(data, COLUMNS));
            check(engine.getLastResult().getThreads() == (threads == 0 ? 4 : threads),
                    "numero de hilos " + threads);
            for (Row row : rows) {
                long expected = row.deleted ? -1 : row.offset;
                check(engine.searchOffset("id", row.id) == expected, "id con " + threads + " hilos");
            }
            check(!hasWorkspace(dir), "workspace residual con " + threads + " hilos");
        }
    }

    private static void testDuplicatesAndDeletes(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("duplicates"));
        Path data = writeFixture(dir, 30, false);
        List<Row> rows = readRows(data);
        ParallelIndexEngine engine = new ParallelIndexEngine(dir);
        engine.rebuildAllIndexes(new TestStorage(data, COLUMNS));

        java.util.Map<String, List<Long>> byDni = new java.util.LinkedHashMap<>();
        for (Row row : rows) {
            byDni.computeIfAbsent(row.dni, key -> new ArrayList<>()).add(row.offset);
        }
        java.util.Map.Entry<String, List<Long>> chosen = null;
        for (java.util.Map.Entry<String, List<Long>> entry : byDni.entrySet()) {
            if (entry.getValue().size() > 1) {
                chosen = entry;
                break;
            }
        }
        check(chosen != null, "la fixture debe tener DNIs repetidos");
        String dni = chosen.getKey();
        List<Long> offsets = chosen.getValue();
        check(engine.searchOffset("dni", dni) == offsets.get(0), "menor offset activo");

        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(data.toFile(), "rw")) {
            file.seek(offsets.get(0));
            file.writeByte(0x01);
        }
        check(engine.searchOffset("dni", dni) == offsets.get(1),
                "borrado logico posterior al indexado");
        engine.rebuildAllIndexes(new TestStorage(data, COLUMNS));
        check(engine.searchOffset("dni", dni) == offsets.get(1), "reindexacion tras borrado");

        for (int i = 1; i < offsets.size(); i++) {
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(data.toFile(), "rw")) {
                file.seek(offsets.get(i));
                file.writeByte(0x01);
            }
        }
        engine.rebuildAllIndexes(new TestStorage(data, COLUMNS));
        check(engine.searchOffset("dni", dni) == -1, "todas las coincidencias borradas");
    }

    private static void testEmptyAndAllDeleted(Path root) throws Exception {
        Path emptyDir = Files.createDirectory(root.resolve("empty"));
        Path empty = Files.createFile(emptyDir.resolve("policies_data.dat"));
        ParallelIndexEngine emptyEngine = new ParallelIndexEngine(emptyDir);
        emptyEngine.rebuildAllIndexes(new TestStorage(empty, COLUMNS));
        check(emptyEngine.getLastResult().getRecordsIndexed() == 0, "indice vacio");
        check(emptyEngine.searchOffset("id", 1L) == -1, "busqueda en indice vacio");
        check(emptyEngine.getIndexedColumns().equals(COLUMNS), "columnas con indice vacio");

        Path deletedDir = Files.createDirectory(root.resolve("all-deleted"));
        Path deleted = writeFixture(deletedDir, 11, false);
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(deleted.toFile(), "rw")) {
            long offset = 0;
            while (offset < file.length()) {
                file.seek(offset);
                file.writeByte(0x01);
                int length = readLengthAt(file, offset);
                offset += 5L + length;
            }
        }
        ParallelIndexEngine deletedEngine = new ParallelIndexEngine(deletedDir);
        deletedEngine.rebuildAllIndexes(new TestStorage(deleted, COLUMNS));
        check(deletedEngine.getLastResult().getRecordsIndexed() == 0, "todo eliminado");
        check(deletedEngine.searchOffset("id", 0L) == -1, "sin registros activos");
    }

    private static void testMalformedData(Path root) throws Exception {
        byte[][] invalid = {
                {0, 0, 0, 0},
                {2, 0, 0, 0, 0},
                {0, -1, -1, -1, -1},
                {0, 0, 0, 0, 4, 9}
        };
        for (int i = 0; i < invalid.length; i++) {
            Path dir = Files.createDirectory(root.resolve("malformed-" + i));
            Path data = dir.resolve("policies_data.dat");
            Files.write(data, invalid[i]);
            byte[] sentinel = {9, 8, 7};
            Files.write(dir.resolve("policies_data_id.idx"), sentinel);
            ParallelIndexEngine engine = new ParallelIndexEngine(dir);
            boolean failed = false;
            try {
                engine.rebuildAllIndexes(new TestStorage(data, COLUMNS));
            } catch (IOException expected) {
                failed = true;
            }
            check(failed, "datos malformados " + i + " deben fallar");
            check(Arrays.equals(sentinel, Files.readAllBytes(dir.resolve("policies_data_id.idx"))),
                    "indice previo conservado " + i);
            check(Arrays.equals(invalid[i], Files.readAllBytes(data)), "datos no modificados " + i);
            check(!hasWorkspace(dir), "workspace residual " + i);
        }
    }

    private static void testInterruption(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("interruption"));
        Path data = writeFixture(dir, 100, false);
        CountDownLatch atCheckpoint = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        IndexScanner.Checkpoint checkpoint = () -> {
            atCheckpoint.countDown();
            release.await();
        };
        ParallelIndexEngine engine = new ParallelIndexEngine(dir, 4, checkpoint);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean restored = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                engine.rebuildAllIndexes(new TestStorage(data, COLUMNS));
            } catch (Throwable failure) {
                outcome.set(failure);
                restored.set(Thread.currentThread().isInterrupted());
            }
        }, "index-cancellation-caller");
        caller.start();
        boolean reached = atCheckpoint.await(30, TimeUnit.SECONDS);
        if (reached) caller.interrupt();
        else release.countDown();
        caller.join(TimeUnit.SECONDS.toMillis(30));
        release.countDown();

        check(reached, "los workers alcanzaron el checkpoint");
        check(!caller.isAlive(), "el llamador termino tras la interrupcion");
        check(outcome.get() instanceof InterruptedException,
                "rebuild debio lanzar InterruptedException: " + outcome.get());
        check(restored.get(), "estado de interrupcion restaurado");
        check(!hasWorkspace(dir), "workspace residual tras cancelacion");
        check(!Files.exists(dir.resolve("policies_data_id.idx")), "no se publico indice incompleto");
    }

    private static void testCorruptIndex(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("corrupt-index"));
        Path data = writeFixture(dir, 9, false);
        ParallelIndexEngine engine = new ParallelIndexEngine(dir);
        engine.rebuildAllIndexes(new TestStorage(data, COLUMNS));
        Files.write(dir.resolve("policies_data_id.idx"), new byte[]{1, 2, 3, 4});
        boolean failed = false;
        try {
            engine.searchOffset("id", 1L);
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, "indice corrupto debe reportar IOException");
    }

    private static void testVolume(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("volume"));
        Path data = writeFixture(dir, 100_000, true);
        byte[] digestBefore = digest(data);
        List<Row> rows = readRows(data);
        ParallelIndexEngine engine = new ParallelIndexEngine(dir, 8);
        engine.rebuildAllIndexes(new TestStorage(data, COLUMNS));
        check(engine.getLastResult().getRecordsIndexed() == countActive(rows),
                "conteo de activos en volumen");
        Random random = new Random(1234);
        Set<Long> checked = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            int index = random.nextInt(rows.size());
            if (!checked.add((long) index)) continue;
            Row row = rows.get(index);
            long expected = row.deleted ? -1 : row.offset;
            check(engine.searchOffset("id", row.id) == expected, "volumen id=" + row.id);
        }
        check(Arrays.equals(digestBefore, digest(data)), "el .dat no se modifico");
        check(!hasWorkspace(dir), "workspace residual en volumen");
    }

    private static Path writeFixture(Path dir, int count, boolean mixed) throws IOException {
        Path file = dir.resolve("policies_data.dat");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (int i = 0; i < count; i++) {
                byte[] payload = payload(i);
                out.writeByte(mixed && i % 5 == 3 ? 0x01 : 0x00);
                out.writeInt(payload.length);
                out.write(payload);
            }
        }
        return file;
    }

    private static byte[] payload(int i) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(i);
            writeString(out, String.format("DNI%05d", i % 7));
            writeString(out, "Titular " + (i % 11));
            writeString(out, i % 2 == 0 ? "Salud Integral" : "Vehicular Todo Riesgo");
            writeString(out, DEPARTAMENTOS[i % DEPARTAMENTOS.length]);
            out.writeDouble(100.0 + i);
            writeString(out, "2026-01-01");
        }
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeShort(encoded.length);
        out.write(encoded);
    }

    private static List<Row> readRows(Path file) throws IOException {
        List<Row> rows = new ArrayList<>();
        long offset = 0;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                int flag = in.read();
                if (flag == -1) break;
                int length = in.readInt();
                if (length < 26) throw new AssertionError("payload de fixture demasiado corto");
                byte[] payload = new byte[length];
                in.readFully(payload);
                rows.add(parseRow(flag == 0x01, offset, payload));
                offset += 5L + length;
            }
        }
        return rows;
    }

    private static Row parseRow(boolean deleted, long offset, byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            long id = in.readLong();
            String dni = readString(in);
            String titular = readString(in);
            String tipoSeguro = readString(in);
            String departamento = readString(in);
            in.readDouble();
            readString(in);
            return new Row(offset, deleted, id, dni, titular, tipoSeguro, departamento);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readUnsignedShort()];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int readLengthAt(java.io.RandomAccessFile file, long offset) throws IOException {
        file.seek(offset + 1);
        return file.readInt();
    }

    private static long expectedOffset(List<Row> rows, String column, String key) {
        long best = Long.MAX_VALUE;
        for (Row row : rows) {
            if (!row.deleted && value(row, column).equals(key)) best = Math.min(best, row.offset);
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    private static String value(Row row, String column) {
        return switch (column) {
            case "dni" -> row.dni;
            case "titular" -> row.titular;
            case "tipoSeguro" -> row.tipoSeguro;
            case "departamento" -> row.departamento;
            default -> throw new IllegalArgumentException(column);
        };
    }

    private static long countActive(List<Row> rows) {
        return rows.stream().filter(row -> !row.deleted).count();
    }

    private static byte[] digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static boolean hasWorkspace(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
            return paths.anyMatch(path ->
                    path.getFileName().toString().startsWith(".lsdfms-index-"));
        }
    }

    private static void removeTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Row {
        final long offset;
        final boolean deleted;
        final long id;
        final String dni;
        final String titular;
        final String tipoSeguro;
        final String departamento;

        Row(long offset, boolean deleted, long id, String dni, String titular,
            String tipoSeguro, String departamento) {
            this.offset = offset;
            this.deleted = deleted;
            this.id = id;
            this.dni = dni;
            this.titular = titular;
            this.tipoSeguro = tipoSeguro;
            this.departamento = departamento;
        }
    }

    private static final class TestStorage implements StorageEngine {
        private final Path dataFile;
        private final Metadata metadata = new Metadata();

        TestStorage(Path dataFile, List<String> columns) {
            this.dataFile = dataFile;
            metadata.setActiveDataFile(dataFile.toString());
            metadata.setIndexedColumns(columns);
        }

        @Override public long insert(InsurancePolicy record) {
            throw new UnsupportedOperationException();
        }

        @Override public InsurancePolicy readAt(long offset) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean markAsDeleted(long offset) {
            throw new UnsupportedOperationException();
        }

        @Override public long getFileSize() throws IOException {
            return Files.size(dataFile);
        }

        @Override public Metadata getMetadata() {
            return metadata;
        }

        @Override public void saveMetadata() {}

        @Override public void close() {}
    }
}
