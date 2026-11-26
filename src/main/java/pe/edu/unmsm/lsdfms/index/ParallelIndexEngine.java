package pe.edu.unmsm.lsdfms.index;

import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * INDEX() paralelo a nivel de tareas: un hilo por columna indexable y busqueda
 * dicotomica O(log n) sobre archivos .idx de longitud variable.
 *
 * Asignado a Jerson (Modulo 4). No marca isIndexSynced: QueueDispatcher hace
 * esa coordinacion despues de que rebuildAllIndexes termine correctamente.
 */
public final class ParallelIndexEngine implements IndexEngine {

    private final Path defaultDirectory;
    private final int threads;
    private final IndexScanner.Checkpoint checkpoint;

    private volatile Path dataFile;
    private volatile List<String> configuredColumns = ColumnKeyExtractor.DEFAULT_COLUMNS;
    private volatile IndexResult lastResult;

    public ParallelIndexEngine() {
        this(Path.of("data"));
    }

    public ParallelIndexEngine(Path directory) {
        this(directory, 0, null);
    }

    /** threads = 0 usa un hilo por columna; si no, 1, 2, 4 u 8. */
    public ParallelIndexEngine(Path directory, int threads) {
        this(directory, threads, null);
    }

    ParallelIndexEngine(Path directory, int threads, IndexScanner.Checkpoint checkpoint) {
        if (threads != 0 && threads != 1 && threads != 2 && threads != 4 && threads != 8) {
            throw new IllegalArgumentException("Threads must be 0 (one per column), 1, 2, 4, or 8");
        }
        this.defaultDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.threads = threads;
        this.checkpoint = checkpoint;
        this.dataFile = defaultDirectory.resolve("policies_data.dat");
    }

    @Override
    public synchronized void rebuildAllIndexes(StorageEngine storage)
            throws IOException, InterruptedException {
        Objects.requireNonNull(storage, "storage no puede ser null");
        long started = System.nanoTime();
        Path source = Path.of(storage.getMetadata().getActiveDataFile()).toAbsolutePath().normalize();
        this.dataFile = source;
        List<String> columns = resolveColumns(storage.getMetadata().getIndexedColumns());
        this.configuredColumns = columns;
        if (columns.isEmpty()) {
            this.lastResult = new IndexResult(columns, 0, 0, 0, 0, 0, List.of(), source);
            return;
        }

        Path parent = source.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Directorio de indices no disponible: " + parent);
        }
        Path workspace = Files.createTempDirectory(parent, ".lsdfms-index-");
        Path snapshot = workspace.resolve("data.snapshot");
        ExecutorService executor = null;
        List<Future<ColumnIndexTask.Statistics>> futures = new ArrayList<>();
        List<Path> temporaries = new ArrayList<>();
        boolean interruptionObserved = false;
        try {
            copySnapshot(source, snapshot);
            int poolSize = threads == 0 ? columns.size() : threads;
            executor = Executors.newFixedThreadPool(poolSize, task -> {
                Thread thread = new Thread(task, "IndexWorker-" + System.nanoTime() % 1000);
                thread.setDaemon(true);
                return thread;
            });
            for (String column : columns) {
                Path temporary = workspace.resolve(column + ".idx.tmp");
                temporaries.add(temporary);
                futures.add(executor.submit(new ColumnIndexTask(snapshot, temporary,
                        ColumnKeyExtractor.forColumn(column), checkpoint)));
            }

            List<ColumnIndexTask.Statistics> statistics = new ArrayList<>();
            try {
                for (Future<ColumnIndexTask.Statistics> future : futures) {
                    statistics.add(future.get());
                }
            } catch (ExecutionException e) {
                for (Future<?> future : futures) future.cancel(true);
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof InterruptedException interruption) throw interruption;
                throw new IOException("Falló la construcción de un índice", cause);
            } catch (InterruptedException e) {
                interruptionObserved = true;
                for (Future<?> future : futures) future.cancel(true);
                throw e;
            }

            long indexed = statistics.get(0).activeRecords;
            long read = statistics.get(0).recordsRead;
            for (int i = 0; i < statistics.size(); i++) {
                ColumnIndexTask.Statistics item = statistics.get(i);
                if (item.activeRecords != indexed || item.recordsRead != read) {
                    throw new IOException("Los índices no coinciden en el número de registros");
                }
                try (RandomAccessFile file = new RandomAccessFile(temporaries.get(i).toFile(), "r")) {
                    IndexFileFormat.Header header = IndexFileFormat.read(file);
                    if (!header.column.equals(columns.get(i)) || header.entryCount != indexed) {
                        throw new IOException("Índice " + item.column + " incompleto");
                    }
                }
            }

            List<Path> published = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                Path target = indexPath(columns.get(i));
                publish(temporaries.get(i), target);
                published.add(target);
            }
            this.lastResult = new IndexResult(columns, read, indexed,
                    statistics.get(0).deletedRecords,
                    (System.nanoTime() - started) / 1_000_000L,
                    threads == 0 ? columns.size() : threads, published, source);
        } finally {
            if (executor != null) {
                executor.shutdownNow();
                for (Future<?> future : futures) if (!future.isDone()) future.cancel(true);
                boolean terminated = false;
                while (!terminated) {
                    try {
                        terminated = executor.awaitTermination(1, TimeUnit.DAYS);
                    } catch (InterruptedException e) {
                        interruptionObserved = true;
                        executor.shutdownNow();
                    }
                }
            }
            try {
                deleteTree(workspace);
            } finally {
                if (interruptionObserved) Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public long searchOffset(String column, Object key) throws IOException {
        ColumnKeyExtractor extractor = ColumnKeyExtractor.forColumn(column);
        if (extractor == null) return -1;
        IndexKey query = extractor.queryKey(key);
        if (query == null) return -1;
        Path indexFile = indexPath(column);
        if (!Files.isRegularFile(indexFile)) return -1;

        try (RandomAccessFile index = new RandomAccessFile(indexFile.toFile(), "r")) {
            IndexFileFormat.Header header = IndexFileFormat.read(index);
            if (!header.column.equals(column) || header.kind != extractor.kind()) return -1;

            long low = 0;
            long high = header.entryCount - 1;
            long found = -1;
            while (low <= high) {
                long middle = (low + high) >>> 1;
                IndexFileFormat.EntryRef entry = IndexFileFormat.readEntry(index, header, middle);
                int comparison = IndexFileFormat.readKey(index, header, entry).compareTo(query);
                if (comparison < 0) {
                    low = middle + 1;
                } else {
                    if (comparison == 0) found = middle;
                    high = middle - 1;
                }
            }
            if (found < 0) return -1;

            Path data = this.dataFile;
            if (data == null || !Files.isRegularFile(data)) return -1;
            try (RandomAccessFile records = new RandomAccessFile(data.toFile(), "r")) {
                for (long i = found; i < header.entryCount; i++) {
                    IndexFileFormat.EntryRef entry = IndexFileFormat.readEntry(index, header, i);
                    if (IndexFileFormat.readKey(index, header, entry).compareTo(query) != 0) break;
                    if (isActive(records, entry.recordOffset)) return entry.recordOffset;
                }
            }
            return -1;
        }
    }

    @Override
    public List<String> getIndexedColumns() {
        List<String> available = new ArrayList<>();
        for (String column : configuredColumns) {
            if (Files.isRegularFile(indexPath(column))) available.add(column);
        }
        return Collections.unmodifiableList(available);
    }

    public IndexResult getLastResult() {
        return lastResult;
    }

    private static boolean isActive(RandomAccessFile data, long offset) throws IOException {
        long length = data.length();
        if (offset < 0 || length - offset < 5) return false;
        data.seek(offset);
        int flag = data.readUnsignedByte();
        int recordLength = data.readInt();
        return flag == 0x00 && recordLength >= 26 && offset + 5L + recordLength <= length;
    }

    private static List<String> resolveColumns(List<String> configured) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        if (configured != null) {
            for (String column : configured) {
                if (ColumnKeyExtractor.forColumn(column) != null) columns.add(column);
            }
        }
        return List.copyOf(columns);
    }

    private static void copySnapshot(Path source, Path snapshot) throws IOException {
        if (!Files.exists(source)) {
            Files.createFile(snapshot);
            return;
        }
        long originalSize = Files.size(source);
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(snapshot, StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            long position = 0;
            while (position < originalSize) {
                buffer.clear();
                int count = input.read(buffer, position);
                if (count < 0) throw new IOException("El archivo de datos terminó durante el snapshot");
                if (count == 0) continue;
                buffer.flip();
                while (buffer.hasRemaining()) output.write(buffer);
                position += count;
            }
            output.force(true);
        }
        if (Files.size(source) != originalSize) {
            throw new IOException("El archivo de datos cambió durante la indexación");
        }
    }

    private static void publish(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            if (Files.exists(target)) {
                throw new IOException("Sin movimiento atómico: el índice existente se conservó", e);
            }
            Files.move(temporary, target);
        }
    }

    private Path indexPath(String column) {
        Path file = this.dataFile;
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return file.resolveSibling(base + "_" + column + ".idx");
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        IOException failure = null;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
