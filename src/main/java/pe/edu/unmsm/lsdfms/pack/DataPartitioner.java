package pe.edu.unmsm.lsdfms.pack;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finds near byte-balanced cuts in one sequential pass, storing only the requested cuts. */
public final class DataPartitioner {
    private DataPartitioner() { }

    public static List<DataPartition> partition(Path file, int requestedPartitions) throws IOException {
        if (requestedPartitions < 1) throw new IllegalArgumentException("Partition count must be positive");
        List<DataPartition> result = new ArrayList<>();
        long fileLength = Files.size(file);
        if (fileLength == 0) return result;

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
            long previousBoundary = 0;
            long previousCut = 0;
            long nextTargetPart = 1;
            long offset = 0;
            while (offset < fileLength) {
                if (fileLength - offset < 5) throw new EOFException("Truncated record header at offset " + offset);
                int flag = input.readUnsignedByte();
                if (flag != 0 && flag != 1) throw new IOException("Invalid deleted flag at offset " + offset);
                int recordLength = input.readInt();
                if (recordLength < 0 || recordLength > fileLength - offset - 5) {
                    throw new IOException("Invalid record length at offset " + offset + ": " + recordLength);
                }
                long boundary = offset + 5L + recordLength;
                input.skipNBytes(recordLength);

                while (nextTargetPart < requestedPartitions) {
                    long target = (fileLength / requestedPartitions) * nextTargetPart
                            + ((fileLength % requestedPartitions) * nextTargetPart) / requestedPartitions;
                    if (boundary < target) break;
                    long candidate = target - previousBoundary <= boundary - target ? previousBoundary : boundary;
                    if (candidate > previousCut && candidate < fileLength) {
                        result.add(new DataPartition(previousCut, candidate));
                        previousCut = candidate;
                    }
                    // One physical boundary can satisfy multiple targets; never emit empty/overlapping ranges.
                    do { nextTargetPart++; }
                    while (nextTargetPart < requestedPartitions &&
                            (fileLength / requestedPartitions) * nextTargetPart
                                    + ((fileLength % requestedPartitions) * nextTargetPart) / requestedPartitions <= candidate);
                }
                previousBoundary = boundary;
                offset = boundary;
            }
            if (offset != fileLength) throw new IOException("File ends between records");
            if (previousCut < fileLength) result.add(new DataPartition(previousCut, fileLength));
        }
        return result;
    }
}
