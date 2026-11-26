package pe.edu.unmsm.lsdfms.pack;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Parallel physical compaction. Output is a separate sibling; the source is never replaced. */
public final class ParallelPackEngine {
    private final PartitionWorker.Checkpoint checkpoint;
    private final MoveOperation moveOperation;

    public ParallelPackEngine() { this(null, null); }

    ParallelPackEngine(PartitionWorker.Checkpoint checkpoint, MoveOperation moveOperation) {
        this.checkpoint = checkpoint;
        this.moveOperation = moveOperation;
    }

    public PackResult pack(Path source, int threads) throws IOException, InterruptedException {
        Path absolute = source.toAbsolutePath().normalize();
        Path output = absolute.resolveSibling(absolute.getFileName().toString() + ".packed");
        return pack(source, output, threads);
    }

    /** Compacts source to output; output may not be the source itself. */
    public PackResult pack(Path source, Path output, int threads) throws IOException, InterruptedException {
        if (threads != 2 && threads != 4 && threads != 8) throw new IllegalArgumentException("Threads must be 2, 4, or 8");
        Path input = source.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(input) || !Files.isReadable(input)) throw new IOException("Input must be a readable regular file: " + input);
        if (input.equals(destination)) throw new IllegalArgumentException("PACK output must differ from source");
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Output directory does not exist: " + parent);
        long started = System.nanoTime();
        long originalSize = Files.size(input);
        Path workspace = Files.createTempDirectory(parent, ".lsdfms-pack-");
        Path snapshot = workspace.resolve("source.snapshot");
        Path staged = workspace.resolve("compacted.output");
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Path> blocks = new ArrayList<>();
        List<Future<PartitionWorker.Statistics>> futures = new ArrayList<>();
        boolean interruptionObserved = false;
        try {
            // FileLock coordinates only with writers that acquire the same lock; it is advisory.
            try (FileChannel channel = FileChannel.open(input, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock();
                 FileChannel snapshotChannel = FileChannel.open(snapshot, StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                long position = 0;
                while (position < channel.size()) {
                    buffer.clear();
                    int count = channel.read(buffer, position);
                    if (count < 0) throw new IOException("Source ended while taking PACK snapshot");
                    if (count == 0) continue;
                    buffer.flip();
                    while (buffer.hasRemaining()) snapshotChannel.write(buffer);
                    position += count;
                }
                snapshotChannel.force(true);
            }
            if (Files.size(snapshot) != originalSize) throw new IOException("Source changed while taking PACK snapshot");
            List<DataPartition> partitions = DataPartitioner.partition(snapshot, threads);
            for (int i = 0; i < partitions.size(); i++) {
                Path block = workspace.resolve("partition-" + i + ".tmp");
                blocks.add(block);
                futures.add(executor.submit(new PartitionWorker(snapshot, block, partitions.get(i), checkpoint)));
            }
            long read = 0, kept = 0, discarded = 0;
            try {
                for (Future<PartitionWorker.Statistics> future : futures) {
                    PartitionWorker.Statistics stats = future.get();
                    read += stats.getRecordsRead(); kept += stats.getRecordsKept(); discarded += stats.getRecordsDiscarded();
                }
            } catch (ExecutionException e) {
                for (Future<?> future : futures) future.cancel(true);
                Throwable cause = e.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                throw new IOException("PACK worker failed", cause);
            }
            if (read != kept + discarded) throw new IOException("Worker statistics are inconsistent");
            BlockMerger.MergeStatistics merged = new BlockMerger().merge(blocks, staged);
            if (merged.getRecords() != kept) throw new IOException("Merged output record count mismatch");
            long compactedSize = Files.size(staged);
            if (compactedSize != merged.getSize()) throw new IOException("Merged output size mismatch");
            try {
                move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Never use a non-atomic move to replace an existing destination.
                if (Files.exists(destination)) {
                    throw new IOException("Atomic publication is unavailable; existing destination was preserved", e);
                }
                try {
                    // No REPLACE_EXISTING: a destination created concurrently is not overwritten.
                    move(staged, destination);
                } catch (IOException fallbackFailure) {
                    fallbackFailure.addSuppressed(e);
                    throw fallbackFailure;
                }
            }
            return new PackResult(read, kept, discarded, originalSize, compactedSize,
                    (System.nanoTime() - started) / 1_000_000L, threads, input, destination);
        } catch (InterruptedException e) {
            interruptionObserved = true;
            for (Future<?> future : futures) future.cancel(true);
            throw e;
        } finally {
            executor.shutdownNow();
            for (Future<?> future : futures) if (!future.isDone()) future.cancel(true);
            boolean terminated = false;
            while (!terminated) {
                try { terminated = executor.awaitTermination(1, java.util.concurrent.TimeUnit.DAYS); }
                catch (InterruptedException e) { interruptionObserved = true; executor.shutdownNow(); }
            }
            try { deleteTree(workspace); }
            finally {
                // Restore even when interruption arrived during Future.get or executor shutdown.
                if (interruptionObserved) Thread.currentThread().interrupt();
            }
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        IOException failure = null;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                try { Files.deleteIfExists(path); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
        }
        if (failure != null) throw failure;
    }

    private Path move(Path source, Path destination, CopyOption... options) throws IOException {
        return moveOperation == null ? Files.move(source, destination, options)
                : moveOperation.move(source, destination, options);
    }

    interface MoveOperation {
        Path move(Path source, Path destination, CopyOption... options) throws IOException;
    }
}
