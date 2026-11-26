package pe.edu.unmsm.lsdfms.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Sequentially merges partition outputs in partition order without buffering whole files. */
public final class BlockMerger {
    public MergeStatistics merge(List<Path> blocks, Path destination) throws IOException {
        long bytes = 0, records = 0;
        boolean completed = false;
        try {
            try (OutputStream output = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                for (Path block : blocks) {
                    try (InputStream input = Files.newInputStream(block)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); bytes += count; }
                    }
                }
            }
            try (java.io.RandomAccessFile check = new java.io.RandomAccessFile(destination.toFile(), "r")) {
                long offset = 0;
                while (offset < check.length()) {
                    if (check.length() - offset < 5) throw new IOException("Invalid merged file header");
                    check.seek(offset);
                    int flag = check.readUnsignedByte(); int size = check.readInt();
                    if (flag != 0 || size < 0 || size > check.length() - offset - 5) throw new IOException("Invalid compacted record");
                    offset += 5L + size; records++;
                }
            }
            completed = true;
            return new MergeStatistics(bytes, records);
        } finally {
            if (!completed) Files.deleteIfExists(destination);
        }
    }
    public static final class MergeStatistics {
        private final long size, records;
        MergeStatistics(long size, long records) { this.size = size; this.records = records; }
        public long getSize() { return size; }
        public long getRecords() { return records; }
    }
}
