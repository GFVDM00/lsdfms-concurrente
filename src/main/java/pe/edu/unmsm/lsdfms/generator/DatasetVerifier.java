package pe.edu.unmsm.lsdfms.generator;

import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.MetadataRepository;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verificador independiente de datasets generados.
 *
 * <p>Recorre cabeceras, valida longitudes y banderas, y compara los conteos
 * contra la metadata persistida. En datasets pequenos tambien deserializa
 * muestras para comprobar que el payload respeta el formato de
 * {@code RafStorageEngine}.</p>
 */
public final class DatasetVerifier {

    private DatasetVerifier() {}

    public static VerificationResult verify(Path dataFile, Path metadataFile) throws IOException {
        List<String> errors = new ArrayList<>();
        if (!Files.isRegularFile(dataFile)) {
            errors.add("No existe el archivo de datos: " + dataFile);
            return new VerificationResult(false, 0, 0, 0, 0, errors);
        }
        if (!Files.isRegularFile(metadataFile)) {
            errors.add("No existe la metadata: " + metadataFile);
            return new VerificationResult(false, 0, 0, 0, 0, errors);
        }

        long total = 0;
        long active = 0;
        long deleted = 0;
        long fileLength = Files.size(dataFile);
        long offset = 0;
        long sampleEvery = 1;
        try (RandomAccessFile file = new RandomAccessFile(dataFile.toFile(), "r")) {
            sampleEvery = Math.max(1, fileLength / 100_000L);
            while (offset < fileLength) {
                if (fileLength - offset < 5) {
                    errors.add("Cabecera truncada en offset " + offset);
                    break;
                }
                file.seek(offset);
                int flag = file.readUnsignedByte();
                int recordLength = file.readInt();
                long next = offset + 5L + recordLength;
                if (flag != 0x00 && flag != 0x01) {
                    errors.add("Bandera invalida " + flag + " en offset " + offset);
                }
                if (recordLength < 26) {
                    errors.add("Longitud de payload invalida " + recordLength + " en offset " + offset);
                }
                if (next < offset || next > fileLength) {
                    errors.add("Registro truncado en offset " + offset);
                    break;
                }
                if (total % sampleEvery == 0 || next == fileLength || offset == 0) {
                    byte[] payload = new byte[recordLength];
                    file.readFully(payload);
                    try {
                        decodePayload(payload);
                    } catch (IOException e) {
                        errors.add("Payload invalido en offset " + offset + ": " + e.getMessage());
                    }
                }
                if (flag == 0x00) {
                    active++;
                } else {
                    deleted++;
                }
                total++;
                offset = next;
            }
        }

        if (offset != fileLength) {
            errors.add("El recorrido termino en " + offset + " y el archivo mide " + fileLength);
        }

        Metadata metadata = new MetadataRepository(metadataFile.toFile()).load();
        if (metadata.getTotalRecords() != total) {
            errors.add("totalRecords=" + metadata.getTotalRecords() + " pero el archivo tiene " + total);
        }
        if (metadata.getActiveRecords() != active) {
            errors.add("activeRecords=" + metadata.getActiveRecords() + " pero el archivo tiene " + active);
        }
        if (metadata.getDeletedRecords() != deleted) {
            errors.add("deletedRecords=" + metadata.getDeletedRecords() + " pero el archivo tiene " + deleted);
        }
        if (metadata.getLastOffset() != fileLength) {
            errors.add("lastOffset=" + metadata.getLastOffset() + " pero el archivo mide " + fileLength);
        }
        Path declared = Path.of(metadata.getActiveDataFile()).toAbsolutePath().normalize();
        if (!declared.equals(dataFile.toAbsolutePath().normalize())) {
            errors.add("activeDataFile=" + declared + " no coincide con " + dataFile);
        }

        return new VerificationResult(errors.isEmpty(), total, active, deleted,
                fileLength, errors);
    }

    public static void requireValid(Path dataFile, Path metadataFile) throws IOException {
        VerificationResult result = verify(dataFile, metadataFile);
        if (!result.isValid()) {
            throw new IOException("Dataset invalido:\n - " + String.join("\n - ", result.getErrors()));
        }
    }

    public static void main(String[] args) {
        Path data = null;
        Path metadata = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> data = Path.of(args[++i]);
                case "--metadata" -> metadata = Path.of(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("Uso: DatasetVerifier --data PATH --metadata PATH");
                    return;
                }
                default -> throw new IllegalArgumentException("Opcion desconocida: " + args[i]);
            }
        }
        if (data == null) {
            data = Path.of("data", "policies_data.dat");
        }
        if (metadata == null) {
            String name = data.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            metadata = data.resolveSibling(base + ".properties");
        }
        try {
            VerificationResult result = verify(data, metadata);
            System.out.println("Dataset : " + data);
            System.out.println("Metadata: " + metadata);
            System.out.println("Registros: " + result.getTotalRecords()
                    + " (activos=" + result.getActiveRecords()
                    + ", eliminados=" + result.getDeletedRecords() + ")");
            System.out.println("Bytes: " + result.getFileLength());
            if (result.isValid()) {
                System.out.println("OK: dataset y metadata son consistentes.");
            } else {
                System.err.println("ERRORES:");
                for (String error : result.getErrors()) {
                    System.err.println(" - " + error);
                }
                System.exit(1);
            }
        } catch (IOException e) {
            System.err.println("Error verificando dataset: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long id = input.readLong();
            if (id <= 0) {
                throw new IOException("id no positivo: " + id);
            }
            readString(input, "dni");
            readString(input, "titular");
            readString(input, "tipoSeguro");
            readString(input, "departamento");
            input.readDouble();
            String fecha = readString(input, "fechaEmision");
            if (fecha.length() != 10 || fecha.charAt(4) != '-' || fecha.charAt(7) != '-') {
                throw new IOException("fechaEmision con formato invalido: " + fecha);
            }
            if (input.available() != 0) {
                throw new IOException("payload con bytes sobrantes: " + input.available());
            }
        } catch (EOFException e) {
            throw new IOException("payload incompleto", e);
        }
    }

    private static String readString(DataInputStream input, String field) throws IOException {
        int length = input.readUnsignedShort();
        if (length > input.available()) {
            throw new IOException("longitud invalida para " + field + ": " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.isEmpty() && !field.equals("titular")) {
            throw new IOException("campo vacio: " + field);
        }
        return value;
    }

    /** Resultado de una verificacion de dataset. */
    public static final class VerificationResult {
        private final boolean valid;
        private final long totalRecords;
        private final long activeRecords;
        private final long deletedRecords;
        private final long fileLength;
        private final List<String> errors;

        VerificationResult(boolean valid, long totalRecords, long activeRecords,
                           long deletedRecords, long fileLength, List<String> errors) {
            this.valid = valid;
            this.totalRecords = totalRecords;
            this.activeRecords = activeRecords;
            this.deletedRecords = deletedRecords;
            this.fileLength = fileLength;
            this.errors = List.copyOf(errors);
        }

        public boolean isValid() { return valid; }
        public long getTotalRecords() { return totalRecords; }
        public long getActiveRecords() { return activeRecords; }
        public long getDeletedRecords() { return deletedRecords; }
        public long getFileLength() { return fileLength; }
        public List<String> getErrors() { return errors; }

        @Override
        public String toString() {
            return "VerificationResult{" +
                    "valid=" + valid +
                    ", totalRecords=" + totalRecords +
                    ", activeRecords=" + activeRecords +
                    ", deletedRecords=" + deletedRecords +
                    ", fileLength=" + fileLength +
                    ", errors=" + errors +
                    '}';
        }
    }
}
