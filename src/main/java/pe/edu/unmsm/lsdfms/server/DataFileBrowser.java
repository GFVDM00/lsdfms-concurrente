package pe.edu.unmsm.lsdfms.server;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lector paginado del archivo de datos activo para la tabla del cliente web.
 * Abre su propio descriptor en modo "r" y nunca escribe.
 */
public final class DataFileBrowser {

    public static final int MAX_PAGE_SIZE = 200;

    public enum Filter {
        ACTIVOS,
        ELIMINADOS,
        TODOS;

        public static Filter parse(String raw) {
            if (raw == null) return ACTIVOS;
            return switch (raw.trim().toLowerCase()) {
                case "eliminados" -> ELIMINADOS;
                case "todos" -> TODOS;
                default -> ACTIVOS;
            };
        }

        boolean accepts(boolean deleted) {
            return switch (this) {
                case ACTIVOS -> !deleted;
                case ELIMINADOS -> deleted;
                case TODOS -> true;
            };
        }
    }

    public static final class Page {
        private final List<InsurancePolicy> policies;
        private final List<Long> offsets;
        private final long matched;
        private final long scanned;
        private final boolean hasMore;

        Page(List<InsurancePolicy> policies, List<Long> offsets,
             long matched, long scanned, boolean hasMore) {
            this.policies = policies;
            this.offsets = offsets;
            this.matched = matched;
            this.scanned = scanned;
            this.hasMore = hasMore;
        }

        public List<InsurancePolicy> getPolicies() { return policies; }
        public List<Long> getOffsets() { return offsets; }
        public long getMatched() { return matched; }
        public long getScanned() { return scanned; }
        public boolean hasMore() { return hasMore; }
    }

    private DataFileBrowser() {}

    public static Page read(File dataFile, Filter filter, int page, int size) throws IOException {
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, page);
        long skip = (long) pageIndex * pageSize;

        List<InsurancePolicy> policies = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();

        if (dataFile == null || !dataFile.isFile()) {
            return new Page(policies, offsets, 0, 0, false);
        }

        long matched = 0;
        long scanned = 0;
        boolean hasMore = false;

        try (RandomAccessFile raf = new RandomAccessFile(dataFile, "r")) {
            long length = raf.length();
            long offset = 0;
            while (offset < length) {
                if (length - offset < 5) break;
                raf.seek(offset);
                int flag = raf.readUnsignedByte();
                int recordLength = raf.readInt();
                if (flag != 0x00 && flag != 0x01) {
                    throw new IOException("Bandera de borrado no valida en offset " + offset);
                }
                long next = offset + 5L + recordLength;
                if (recordLength < 26 || next < offset) {
                    throw new IOException("Longitud de registro no valida en offset " + offset);
                }
                // Registro aun no confirmado en disco: hay una insercion en curso.
                if (next > length) break;

                scanned++;
                boolean deleted = flag == 0x01;
                if (filter.accepts(deleted)) {
                    if (matched >= skip) {
                        if (policies.size() < pageSize) {
                            byte[] payload = new byte[recordLength];
                            raf.readFully(payload);
                            policies.add(deserialize(payload, deleted));
                            offsets.add(offset);
                        } else {
                            hasMore = true;
                        }
                    }
                    matched++;
                }
                offset = next;
            }
        }

        return new Page(policies, offsets, matched, scanned, hasMore);
    }

    private static InsurancePolicy deserialize(byte[] payload, boolean deleted) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long id = input.readLong();
            String dni = readString(input);
            String titular = readString(input);
            String tipoSeguro = readString(input);
            String departamento = readString(input);
            double prima = input.readDouble();
            String fechaEmision = readString(input);
            return new InsurancePolicy(deleted, id, dni, titular, tipoSeguro,
                    departamento, prima, fechaEmision);
        } catch (java.io.EOFException e) {
            throw new IOException("Payload de poliza incompleto", e);
        }
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > input.available()) {
            throw new IOException("Longitud de campo de texto no valida: " + length);
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }
}
