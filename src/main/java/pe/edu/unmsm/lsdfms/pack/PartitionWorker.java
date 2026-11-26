package pe.edu.unmsm.lsdfms.pack;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Filters one aligned source range into an independent temporary file. */
public final class PartitionWorker implements Callable<PartitionWorker.Statistics> {
    private final Path source;
    private final Path temporary;
    private final DataPartition partition;
    private final Checkpoint checkpoint;

    public PartitionWorker(Path source, Path temporary, DataPartition partition) {
        this(source, temporary, partition, null);
    }

    PartitionWorker(Path source, Path temporary, DataPartition partition, Checkpoint checkpoint) {
        this.source = source; this.temporary = temporary; this.partition = partition;
        this.checkpoint = checkpoint;
    }

    @Override public Statistics call() throws IOException, InterruptedException {
        long read = 0, kept = 0, discarded = 0, bytes = 0;
        boolean completed = false;
        try (RandomAccessFile input = new RandomAccessFile(source.toFile(), "r");
             RandomAccessFile output = new RandomAccessFile(temporary.toFile(), "rw")) {
            checkInterrupted();
            output.setLength(0);
            long cursor = partition.getStartOffset();
            if (partition.getEndOffset() > input.length()) throw new IOException("Partition exceeds input length");
            while (cursor < partition.getEndOffset()) {
                checkInterrupted();
                if (partition.getEndOffset() - cursor < 5) throw new EOFException("Truncated record in " + partition);
                input.seek(cursor);
                int flag = input.readUnsignedByte();
                int length = input.readInt();
                if ((flag != 0 && flag != 1) || length < 0 || length > partition.getEndOffset() - cursor - 5) {
                    throw new IOException("Invalid record at offset " + cursor);
                }
                long recordBytes = 5L + length;
                read++;
                if (flag == 0) {
                    input.seek(cursor);
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = recordBytes;
                    while (remaining > 0) {
                        checkInterrupted();
                        int count = (int) Math.min(buffer.length, remaining);
                        input.readFully(buffer, 0, count);
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                    kept++; bytes += recordBytes;
                } else discarded++;
                cursor += recordBytes;
                if (checkpoint != null) checkpoint.afterRecord();
            }
            if (cursor != partition.getEndOffset()) throw new IOException("Partition ends inside a record");
            completed = true;
        } finally {
            if (!completed) java.nio.file.Files.deleteIfExists(temporary);
        }
        return new Statistics(read, kept, discarded, bytes);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("PACK partition cancelled");
    }

    interface Checkpoint {
        void afterRecord() throws InterruptedException;
    }

    public static final class Statistics {
        private final long recordsRead, recordsKept, recordsDiscarded, bytesWritten;
        Statistics(long read, long kept, long discarded, long bytes) {
            recordsRead = read; recordsKept = kept; recordsDiscarded = discarded; bytesWritten = bytes;
        }
        public long getRecordsRead() { return recordsRead; }
        public long getRecordsKept() { return recordsKept; }
        public long getRecordsDiscarded() { return recordsDiscarded; }
        public long getBytesWritten() { return bytesWritten; }
    }
}
