package pe.edu.unmsm.lsdfms.pack;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Filters one aligned source range into an independent temporary file. */
public final class PartitionWorker implements Callable<PartitionWorker.Statistics> {
    private static final int INPUT_BUFFER = 1 << 20;
    private static final int OUTPUT_BUFFER = 64 * 1024;
    private static final int COPY_BUFFER = 64 * 1024;

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
        long start = partition.getStartOffset();
        long end = partition.getEndOffset();
        if (end > Files.size(source)) throw new IOException("Partition exceeds input length");

        try (DataInputStream input = new DataInputStream(
                     new BufferedInputStream(Files.newInputStream(source), INPUT_BUFFER));
             DataOutputStream output = new DataOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(temporary), OUTPUT_BUFFER))) {
            checkInterrupted();
            input.skipNBytes(start);
            byte[] copyBuffer = new byte[COPY_BUFFER];
            long cursor = start;
            while (cursor < end) {
                checkInterrupted();
                if (end - cursor < 5) throw new EOFException("Truncated record in " + partition);
                int flag = input.readUnsignedByte();
                int length = input.readInt();
                long recordBytes = 5L + length;
                if ((flag != 0 && flag != 1) || length < 0 || recordBytes > end - cursor) {
                    throw new IOException("Invalid record at offset " + cursor);
                }
                read++;
                if (flag == 0) {
                    output.writeByte(0x00);
                    output.writeInt(length);
                    int remaining = length;
                    while (remaining > 0) {
                        checkInterrupted();
                        int count = Math.min(copyBuffer.length, remaining);
                        input.readFully(copyBuffer, 0, count);
                        output.write(copyBuffer, 0, count);
                        remaining -= count;
                    }
                    kept++;
                    bytes += recordBytes;
                } else {
                    input.skipNBytes(length);
                    discarded++;
                }
                cursor += recordBytes;
                if (checkpoint != null) checkpoint.afterRecord();
            }
            if (cursor != end) throw new IOException("Partition ends inside a record");
            completed = true;
        } finally {
            if (!completed) Files.deleteIfExists(temporary);
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
