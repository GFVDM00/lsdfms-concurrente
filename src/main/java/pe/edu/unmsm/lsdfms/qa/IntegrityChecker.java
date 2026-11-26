package pe.edu.unmsm.lsdfms.qa;

import pe.edu.unmsm.lsdfms.index.IndexEngine;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verificador de integridad entre el archivo de datos, su metadata y los
 * indices reconstruidos.
 *
 * <p>Comprueba cabeceras, offsets, deserializacion, conteos, unicidad de ids,
 * busquedas por id y por columnas de texto, y que los registros eliminados no
 * aparezcan como activos en los indices.</p>
 */
public final class IntegrityChecker {

    private static final List<String> TEXT_COLUMNS =
            List.of("dni", "titular", "tipoSeguro", "departamento");

    private IntegrityChecker() {}

    public static IntegrityReport check(Path dataFile, Path metadataFile,
                                        IndexEngine index, boolean requireIndexSynced)
            throws IOException {
        return check(dataFile, metadataFile, index, requireIndexSynced, 10_000, 2_000);
    }

    /**
     * Variante con presupuesto de comprobaciones de indice. Los conteos y el
     * recorrido del archivo son completos; para datasets grandes se muestrean
     * ids y valores de texto de forma sistematica.
     */
    public static IntegrityReport check(Path dataFile, Path metadataFile,
                                        IndexEngine index, boolean requireIndexSynced,
                                        int maxIdChecks, int maxTextChecks)
            throws IOException {
        if (maxIdChecks <= 0 || maxTextChecks <= 0) {
            throw new IllegalArgumentException("Los limites de comprobacion deben ser > 0");
        }
        IntegrityReport report = new IntegrityReport();
        if (!Files.isRegularFile(dataFile)) {
            report.addError("No existe el archivo de datos: " + dataFile);
            return report;
        }
        if (!Files.isRegularFile(metadataFile)) {
            report.addError("No existe la metadata: " + metadataFile);
            return report;
        }

        Metadata metadata = new MetadataRepository(metadataFile.toFile()).load();
        List<RecordInfo> records = scan(dataFile, report);
        long active = records.stream().filter(record -> !record.deleted).count();
        long deleted = records.size() - active;
        report.setCounts(records.size(), active, deleted);

        if (records.size() != metadata.getTotalRecords()) {
            report.addError("totalRecords=" + metadata.getTotalRecords()
                    + " pero el archivo tiene " + records.size());
        }
        if (active != metadata.getActiveRecords()) {
            report.addError("activeRecords=" + metadata.getActiveRecords()
                    + " pero el archivo tiene " + active);
        }
        if (deleted != metadata.getDeletedRecords()) {
            report.addError("deletedRecords=" + metadata.getDeletedRecords()
                    + " pero el archivo tiene " + deleted);
        }
        if (metadata.getLastOffset() != Files.size(dataFile)) {
            report.addError("lastOffset=" + metadata.getLastOffset()
                    + " pero el archivo mide " + Files.size(dataFile));
        }
        Path declared = Path.of(metadata.getActiveDataFile()).toAbsolutePath().normalize();
        if (!declared.equals(dataFile.toAbsolutePath().normalize())) {
            report.addError("activeDataFile=" + declared + " no coincide con " + dataFile);
        }
        if (requireIndexSynced && !metadata.isIndexSynced()) {
            report.addError("La metadata indica isIndexSynced=false");
        }

        Set<Long> ids = new HashSet<>();
        Set<Long> offsets = new HashSet<>();
        for (RecordInfo record : records) {
            if (!ids.add(record.id())) {
                report.addError("id duplicado: " + record.id());
            }
            if (!offsets.add(record.offset())) {
                report.addError("offset duplicado: " + record.offset());
            }
        }

        if (index == null) {
            if (requireIndexSynced) {
                report.addError("No se proporciono un IndexEngine para verificar indices");
            }
            return report;
        }

        List<String> indexedColumns = index.getIndexedColumns();
        if (indexedColumns == null || indexedColumns.isEmpty()) {
            report.addError("El motor de indices no reporta columnas indexadas");
            return report;
        }

        Map<Long, RecordInfo> byOffset = new HashMap<>();
        for (RecordInfo record : records) {
            byOffset.put(record.offset(), record);
        }

        for (RecordInfo record : sample(records, maxIdChecks)) {
            long found = index.searchOffset("id", record.id());
            report.incrementIndexChecks(1);
            if (record.deleted()) {
                if (found != -1) {
                    report.addError("Registro eliminado id=" + record.id()
                            + " aparece en el indice en offset " + found);
                }
            } else if (found != record.offset()) {
                report.addError("id=" + record.id() + " esperado en offset "
                        + record.offset() + " pero el indice devolvio " + found);
            }
        }

        List<RecordInfo> activeRecords = records.stream()
                .filter(record -> !record.deleted())
                .toList();
        List<RecordInfo> textSample = sample(activeRecords, maxTextChecks);
        for (String column : TEXT_COLUMNS) {
            if (!indexedColumns.contains(column)) {
                continue;
            }
            for (RecordInfo record : textSample) {
                String key = record.value(column);
                long found = index.searchOffset(column, key);
                report.incrementIndexChecks(1);
                if (found < 0) {
                    report.addError("No se encontro " + column + "=" + key
                            + " del offset " + record.offset());
                    continue;
                }
                RecordInfo candidate = byOffset.get(found);
                if (candidate == null || candidate.deleted()) {
                    report.addError("El indice " + column + " apunta a un offset "
                            + found + " inexistente o eliminado");
                } else if (!candidate.value(column).equals(key)) {
                    report.addError("El indice " + column + " devolvio un registro "
                            + "con valor distinto en offset " + found);
                }
            }
        }

        return report;
    }

    private static List<RecordInfo> scan(Path dataFile, IntegrityReport report)
            throws IOException {
        List<RecordInfo> records = new ArrayList<>();
        long length = Files.size(dataFile);
        long offset = 0;
        try (RandomAccessFile file = new RandomAccessFile(dataFile.toFile(), "r")) {
            while (offset < length) {
                if (length - offset < 5) {
                    report.addError("Cabecera truncada en offset " + offset);
                    break;
                }
                file.seek(offset);
                int flag = file.readUnsignedByte();
                int recordLength = file.readInt();
                long next = offset + 5L + recordLength;
                if (flag != 0x00 && flag != 0x01) {
                    report.addError("Bandera invalida " + flag + " en offset " + offset);
                }
                if (recordLength < 26 || next < offset || next > length) {
                    report.addError("Longitud invalida " + recordLength + " en offset " + offset);
                    break;
                }
                byte[] payload = new byte[recordLength];
                file.readFully(payload);
                try {
                    PolicyPayload policy = decode(payload);
                    records.add(new RecordInfo(offset, policy.id(), flag == 0x01, policy));
                } catch (IOException e) {
                    report.addError("Payload invalido en offset " + offset + ": " + e.getMessage());
                }
                offset = next;
            }
        }
        return records;
    }

    private static <T> List<T> sample(List<T> values, int limit) {
        if (limit >= values.size()) {
            return values;
        }
        List<T> sampled = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int index = (int) ((long) i * values.size() / limit);
            sampled.add(values.get(index));
        }
        return sampled;
    }

    private static PolicyPayload decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long id = input.readLong();
            if (id <= 0) {
                throw new IOException("id no positivo: " + id);
            }
            String dni = readString(input);
            String titular = readString(input);
            String tipoSeguro = readString(input);
            String departamento = readString(input);
            double prima = input.readDouble();
            String fechaEmision = readString(input);
            if (input.available() != 0) {
                throw new IOException("bytes sobrantes: " + input.available());
            }
            return new PolicyPayload(id, dni, titular, tipoSeguro, departamento,
                    prima, fechaEmision);
        } catch (EOFException e) {
            throw new IOException("payload incompleto", e);
        }
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > input.available()) {
            throw new IOException("longitud de texto invalida: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record PolicyPayload(long id, String dni, String titular, String tipoSeguro,
                                 String departamento, double prima, String fechaEmision) {}

    private record RecordInfo(long offset, long id, boolean deleted, PolicyPayload policy) {
        String value(String column) {
            return switch (column) {
                case "dni" -> policy.dni();
                case "titular" -> policy.titular();
                case "tipoSeguro" -> policy.tipoSeguro();
                case "departamento" -> policy.departamento();
                default -> throw new IllegalArgumentException("Columna no textual: " + column);
            };
        }
    }
}
