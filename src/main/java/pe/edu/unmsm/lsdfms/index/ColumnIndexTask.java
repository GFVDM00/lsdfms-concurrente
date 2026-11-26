package pe.edu.unmsm.lsdfms.index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/** Una tarea por columna: construye un .idx propio sin compartir estado ni archivos. */
final class ColumnIndexTask implements Callable<ColumnIndexTask.Statistics> {

    static final class IndexEntry {
        final IndexKey key;
        final long offset;

        IndexEntry(IndexKey key, long offset) {
            this.key = key;
            this.offset = offset;
        }
    }

    static final class Statistics {
        final String column;
        final long recordsRead;
        final long activeRecords;
        final long deletedRecords;
        final long fileSize;

        Statistics(String column, long recordsRead, long activeRecords,
                   long deletedRecords, long fileSize) {
            this.column = column;
            this.recordsRead = recordsRead;
            this.activeRecords = activeRecords;
            this.deletedRecords = deletedRecords;
            this.fileSize = fileSize;
        }
    }

    private final Path snapshot;
    private final Path output;
    private final ColumnKeyExtractor extractor;
    private final IndexScanner.Checkpoint checkpoint;

    ColumnIndexTask(Path snapshot, Path output, ColumnKeyExtractor extractor,
                    IndexScanner.Checkpoint checkpoint) {
        this.snapshot = snapshot;
        this.output = output;
        this.extractor = extractor;
        this.checkpoint = checkpoint;
    }

    @Override
    public Statistics call() throws Exception {
        List<IndexEntry> entries = new ArrayList<>();
        IndexScanner.Statistics scanned = IndexScanner.scan(snapshot, checkpoint,
                (offset, payload) -> entries.add(new IndexEntry(extractor.extract(payload), offset)));
        entries.sort(Comparator.comparing((IndexEntry e) -> e.key).thenComparingLong(e -> e.offset));

        Path keysTemp = output.resolveSibling(output.getFileName().toString() + ".keys");
        try {
            long keysOffset = IndexFileFormat.HEADER_BYTES
                    + extractor.column().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + entries.size() * (long) IndexFileFormat.ENTRY_BYTES;
            byte[] entryBytes = new byte[IndexFileFormat.ENTRY_BYTES];
            ByteBuffer entryBuffer = ByteBuffer.wrap(entryBytes).order(ByteOrder.BIG_ENDIAN);
            try (RandomAccessFile index = new RandomAccessFile(output.toFile(), "rw");
                 RandomAccessFile keys = new RandomAccessFile(keysTemp.toFile(), "rw")) {
                index.setLength(0);
                keys.setLength(0);
                IndexFileFormat.writeHeader(index, extractor.kind(), extractor.column(), entries.size());
                long nextKeyPosition = keysOffset;
                for (IndexEntry entry : entries) {
                    long keyCode;
                    if (entry.key.isText()) {
                        keyCode = nextKeyPosition;
                        byte[] bytes = entry.key.bytes();
                        keys.writeShort(bytes.length);
                        keys.write(bytes);
                        nextKeyPosition += 2L + bytes.length;
                    } else {
                        keyCode = entry.key.longValue();
                    }
                    entryBuffer.clear();
                    entryBuffer.putLong(keyCode).putLong(entry.offset);
                    index.write(entryBytes);
                }
                keys.seek(0);
                byte[] copyBuffer = new byte[64 * 1024];
                int count;
                while ((count = keys.read(copyBuffer)) != -1) {
                    index.write(copyBuffer, 0, count);
                }
                if (index.getFilePointer() != nextKeyPosition) {
                    throw new IOException("El tamano del indice no coincide con sus entradas");
                }
            }
            return new Statistics(extractor.column(), scanned.recordsRead,
                    scanned.activeRecords, scanned.deletedRecords, Files.size(output));
        } finally {
            Files.deleteIfExists(keysTemp);
        }
    }
}
