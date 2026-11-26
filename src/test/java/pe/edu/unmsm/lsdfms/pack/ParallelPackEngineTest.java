package pe.edu.unmsm.lsdfms.pack;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Executable integration tests with only the JDK; run with -ea. */
public final class ParallelPackEngineTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pack-test-");
        try {
            testScenariosAtAllThreadCounts(root);
            testMalformedFiles(root);
            testExistingDestination(root);
            testAtomicMoveFallback(root);
            testWorkerCancellation(root);
            testEngineCancellation(root);
            testMergeFailureCleanup(root);
            testStreamingVolume(root);
            testPartitionCoverage(root);
            System.out.println("All ParallelPackEngine tests passed.");
        } finally { removeTree(root); }
    }

    private static void testScenariosAtAllThreadCounts(Path root) throws Exception {
        runCase(root, "empty", 0, false);
        runCase(root, "all-active", 37, false);
        runCase(root, "mixed-variable", 127, true);
        testAllDeleted(root);
    }

    private static void testAllDeleted(Path root) throws Exception {
        Path source = root.resolve("all-deleted.dat");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(source))) {
            for (int id = 0; id < 37; id++) writeRecord(out, 1, payload(id));
        }
        byte[] original = Files.readAllBytes(source);
        for (int threads : new int[]{2, 4, 8}) {
            Path destination = root.resolve("all-deleted-" + threads + ".dat");
            PackResult result = new ParallelPackEngine().pack(source, destination, threads);
            check(result.getActiveRecords() == 0, "all-deleted active count");
            check(result.getDeletedRecords() == 37, "all-deleted deleted count");
            check(Files.size(destination) == 0, "all-deleted output must be empty");
            check(Arrays.equals(original, Files.readAllBytes(source)), "all-deleted source changed");
        }
    }

    private static void runCase(Path root, String name, int count, boolean mixed) throws Exception {
        Path source = root.resolve(name + ".dat");
        writeFixture(source, count, mixed);
        byte[] original = Files.readAllBytes(source);
        List<byte[]> expected = expectedActiveRecords(count, mixed);
        for (int threads : new int[]{2, 4, 8}) {
            Path destination = root.resolve(name + "-" + threads + ".dat");
            PackResult result = new ParallelPackEngine().pack(source, destination, threads);
            assertRecords(destination, expected);
            check(Arrays.equals(original, Files.readAllBytes(source)), "source changed: " + name);
            check(result.getOriginalRecords() == count, "original count");
            check(result.getActiveRecords() == expected.size(), "active count");
            check(result.getDeletedRecords() == count - expected.size(), "deleted count");
            check(result.getFinalSize() == Files.size(destination), "output size");
            check(result.getThreadsUsed() == threads, "thread count");
            check(!hasWorkspace(root), "workspace leak");
        }
    }

    private static void testMalformedFiles(Path root) throws Exception {
        assertFailurePreserves(root, "truncated-header", new byte[]{0, 0, 0, 0});
        assertFailurePreserves(root, "invalid-flag", new byte[]{2, 0, 0, 0, 0});
        assertFailurePreserves(root, "negative-length", new byte[]{0, -1, -1, -1, -1});
        assertFailurePreserves(root, "length-too-large", new byte[]{0, 0, 0, 0, 4, 9});
    }

    private static void assertFailurePreserves(Path root, String name, byte[] invalid) throws Exception {
        Path source = root.resolve(name + ".dat"), destination = root.resolve(name + ".out");
        byte[] existing = new byte[]{8, 7, 6};
        Files.write(source, invalid);
        Files.write(destination, existing);
        boolean failed = false;
        try { new ParallelPackEngine().pack(source, destination, 2); }
        catch (IOException expected) { failed = true; }
        check(failed, name + " should fail");
        check(Arrays.equals(invalid, Files.readAllBytes(source)), name + " source changed");
        check(Arrays.equals(existing, Files.readAllBytes(destination)), name + " overwrote existing destination on failure");
        check(!hasWorkspace(root), name + " left temporary workspace");
    }

    private static void testExistingDestination(Path root) throws Exception {
        Path source = root.resolve("replace-source.dat"), destination = root.resolve("existing-output.dat");
        writeFixture(source, 11, false);
        Files.write(destination, new byte[]{1, 2, 3});
        new ParallelPackEngine().pack(source, destination, 4);
        assertRecords(destination, expectedActiveRecords(11, false));
        check(!hasWorkspace(root), "workspace leak replacing destination");
    }

    private static void testAtomicMoveFallback(Path root) throws Exception {
        Path source = root.resolve("fallback-source.dat");
        writeFixture(source, 13, false);
        ParallelPackEngine.MoveOperation unsupportedAtomic = (from, to, options) -> {
            for (java.nio.file.CopyOption option : options) {
                if (option == java.nio.file.StandardCopyOption.ATOMIC_MOVE) {
                    throw new java.nio.file.AtomicMoveNotSupportedException(from.toString(), to.toString(), "test provider");
                }
            }
            return Files.move(from, to, options);
        };

        Path existing = root.resolve("fallback-existing.dat");
        byte[] sentinel = new byte[]{3, 1, 4, 1, 5};
        Files.write(existing, sentinel);
        boolean rejected = false;
        try { new ParallelPackEngine(null, unsupportedAtomic).pack(source, existing, 2); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "unsupported atomic move must reject existing destination");
        check(Arrays.equals(sentinel, Files.readAllBytes(existing)), "existing destination was modified");

        Path absent = root.resolve("fallback-absent.dat");
        new ParallelPackEngine(null, unsupportedAtomic).pack(source, absent, 2);
        assertRecords(absent, expectedActiveRecords(13, false));
        check(!hasWorkspace(root), "fallback workspace leak");
    }

    private static void testWorkerCancellation(Path root) throws Exception {
        Path source = root.resolve("cancel-source.dat"), temp = root.resolve("cancel-worker.tmp");
        writeFixture(source, 1, false);
        Thread.currentThread().interrupt();
        boolean interrupted = false;
        try { new PartitionWorker(source, temp, new DataPartition(0, Files.size(source))).call(); }
        catch (InterruptedException expected) { interrupted = true; }
        finally { Thread.interrupted(); }
        check(interrupted, "worker did not honor interrupt");
        check(!Files.exists(temp), "cancelled worker temporary remains");
    }

    private static void testEngineCancellation(Path root) throws Exception {
        final int threads = 4;
        Path source = root.resolve("cancel-engine-source.dat");
        Path destination = root.resolve("cancel-engine-output.dat");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(source))) {
            for (int id = 0; id < 100_000; id++) {
                byte[] payload = java.nio.ByteBuffer.allocate(256).putInt(id).put(new byte[252]).array();
                writeRecord(out, 0, payload);
            }
        }
        byte[] sourceDigest = digest(source);
        CountDownLatch workersAtCheckpoint = new CountDownLatch(threads);
        CountDownLatch neverReleased = new CountDownLatch(1);
        ConcurrentLinkedQueue<Thread> workerThreads = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        PartitionWorker.Checkpoint checkpoint = () -> {
            workerThreads.add(Thread.currentThread());
            workersAtCheckpoint.countDown();
            neverReleased.await(); // deterministic pause after processing a complete record
        };
        ParallelPackEngine engine = new ParallelPackEngine(checkpoint, null);
        Thread caller = new Thread(() -> {
            try { engine.pack(source, destination, threads); }
            catch (Throwable failure) {
                outcome.set(failure);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "pack-cancellation-test-caller");
        caller.start();
        boolean reached = workersAtCheckpoint.await(30, TimeUnit.SECONDS);
        if (reached) caller.interrupt();
        else { caller.interrupt(); neverReleased.countDown(); }
        caller.join(TimeUnit.SECONDS.toMillis(30));
        neverReleased.countDown();

        check(reached, "workers did not reach deterministic processing checkpoint");
        check(!caller.isAlive(), "pack caller did not terminate after interruption");
        check(outcome.get() instanceof InterruptedException, "pack did not terminate by InterruptedException: " + outcome.get());
        check(interruptRestored.get(), "pack did not restore caller interrupt status");
        for (Thread worker : workerThreads) check(!worker.isAlive(), "worker still alive after pack returned");
        check(!hasWorkspace(root), "workspace remains after engine cancellation");
        check(!Files.exists(destination), "cancelled pack published output");
        check(Arrays.equals(sourceDigest, digest(source)), "source changed during cancellation");
    }

    private static void testMergeFailureCleanup(Path root) throws Exception {
        Path incomplete = root.resolve("incomplete-merge.tmp");
        Files.write(incomplete, new byte[]{4, 5, 6});
        boolean failed = false;
        try {
            new BlockMerger().merge(java.util.Collections.singletonList(root.resolve("missing-block.tmp")), incomplete);
        } catch (IOException expected) { failed = true; }
        check(failed, "missing merge block should fail");
        check(!Files.exists(incomplete), "incomplete merge output was not removed");
    }

    /** Writes and verifies 100k records incrementally; memory use is independent of dataset size. */
    private static void testStreamingVolume(Path root) throws Exception {
        final int total = 100_000;
        Path source = root.resolve("volume.dat"), output = root.resolve("volume-packed.dat");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(source))) {
            for (int id = 0; id < total; id++) writeRecord(out, (id % 3 == 0) ? 1 : 0, payload(id));
        }
        PackResult result = new ParallelPackEngine().pack(source, output, 8);
        long active = 0, seen = 0;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(output))) {
            for (int id = 0; id < total; id++) {
                if (id % 3 == 0) continue;
                int flag = in.readUnsignedByte(), length = in.readInt();
                byte[] actual = new byte[length]; in.readFully(actual);
                check(flag == 0, "deleted record in volume output");
                check(Arrays.equals(actual, payload(id)), "volume order/content at source id " + id);
                seen++;
            }
            check(in.read() == -1, "unexpected extra volume record");
        }
        for (int id = 0; id < total; id++) if (id % 3 != 0) active++;
        check(seen == active, "volume record count");
        check(result.getOriginalRecords() == total && result.getActiveRecords() == active, "volume stats");
        check(result.getDeletedRecords() == total - active, "volume deleted stats");
        check(!hasWorkspace(root), "volume workspace leak");
    }

    private static void testPartitionCoverage(Path root) throws Exception {
        Path source = root.resolve("uneven.dat");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(source))) {
            for (int id = 0; id < 29; id++) writeRecord(out, id % 2, payload(id));
        }
        List<DataPartition> partitions = DataPartitioner.partition(source, 8);
        long cursor = 0;
        for (DataPartition partition : partitions) {
            check(partition.getStartOffset() == cursor, "partition gap/overlap");
            check(partition.getEndOffset() > partition.getStartOffset(), "empty partition");
            cursor = partition.getEndOffset();
        }
        check(cursor == Files.size(source), "partition coverage");
    }

    private static void writeFixture(Path path, int count, boolean mixed) throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            for (int id = 0; id < count; id++) {
                int flag = mixed ? (id % 3 == 1 ? 1 : 0) : 0;
                writeRecord(out, flag, id % 5 == 0 ? new byte[0] : payload(id));
            }
        }
    }

    private static List<byte[]> expectedActiveRecords(int count, boolean mixed) {
        List<byte[]> expected = new ArrayList<>();
        for (int id = 0; id < count; id++) {
            boolean deleted = mixed && id % 3 == 1;
            if (!deleted) expected.add(encodedRecord(0, id % 5 == 0 ? new byte[0] : payload(id)));
        }
        return expected;
    }

    private static void assertRecords(Path path, List<byte[]> expected) throws IOException {
        int record = 0;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            int flag;
            while ((flag = in.read()) != -1) {
                int length = in.readInt();
                byte[] payload = new byte[length]; in.readFully(payload);
                check(record < expected.size(), "duplicate/unexpected output record");
                check(Arrays.equals(encodedRecord(flag, payload), expected.get(record)), "record mismatch/order at " + record);
                record++;
            }
        }
        check(record == expected.size(), "record omitted: expected " + expected.size() + ", got " + record);
    }

    private static void writeRecord(DataOutputStream out, int flag, byte[] payload) throws IOException {
        out.writeByte(flag); out.writeInt(payload.length); out.write(payload);
    }
    private static byte[] encodedRecord(int flag, byte[] payload) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(payload.length + 5);
            DataOutputStream out = new DataOutputStream(bytes); writeRecord(out, flag, payload); out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] payload(int id) {
        if (id % 5 == 0) return new byte[0];
        return java.nio.ByteBuffer.allocate(12 + id % 19).putInt(id).putLong(~(long) id)
                .put(new byte[id % 19]).array();
    }
    private static byte[] digest(Path path) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }
    private static boolean hasWorkspace(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            return paths.anyMatch(path -> path.getFileName().toString().startsWith(".lsdfms-pack-"));
        }
    }
    private static void removeTree(Path root) throws IOException {
        if (Files.exists(root)) try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
