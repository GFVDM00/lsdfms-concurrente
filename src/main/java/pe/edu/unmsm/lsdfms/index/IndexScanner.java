package pe.edu.unmsm.lsdfms.index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Recorre el archivo .dat por cabeceras (flag + longitud) sin deserializar
 * registros eliminados. Expone solo payloads de registros activos.
 */
final class IndexScanner {

    @FunctionalInterface
    interface RecordVisitor {
        void visit(long offset, byte[] payload) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Checkpoint {
        void check() throws IOException, InterruptedException;
    }

    static final class Statistics {
        final long recordsRead;
        final long activeRecords;
        final long deletedRecords;

        Statistics(long recordsRead, long activeRecords, long deletedRecords) {
            this.recordsRead = recordsRead;
            this.activeRecords = activeRecords;
            this.deletedRecords = deletedRecords;
        }
    }

    private IndexScanner() {}

    static Statistics scan(Path source, Checkpoint checkpoint, RecordVisitor visitor)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Archivo de datos no encontrado: " + source);
        }
        long read = 0;
        long active = 0;
        long deleted = 0;
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "r")) {
            long length = file.length();
            long offset = 0;
            while (offset < length) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                if (length - offset < 5) {
                    throw new IOException("Cabecera de registro truncada en offset " + offset);
                }
                file.seek(offset);
                int flag = file.readUnsignedByte();
                int recordLength = file.readInt();
                if (flag != 0x00 && flag != 0x01) {
                    throw new IOException("Bandera de borrado no valida en offset " + offset);
                }
                long next = offset + 5L + recordLength;
                if (recordLength < 26 || next < offset || next > length) {
                    throw new IOException("Registro truncado o longitud no valida en offset " + offset);
                }
                if (checkpoint != null) {
                    checkpoint.check();
                }
                if (flag == 0x00) {
                    byte[] payload = new byte[recordLength];
                    file.readFully(payload);
                    visitor.visit(offset, payload);
                    active++;
                } else {
                    deleted++;
                }
                read++;
                offset = next;
            }
        }
        return new Statistics(read, active, deleted);
    }
}
