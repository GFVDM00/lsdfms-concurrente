package pe.edu.unmsm.lsdfms.qa;

import pe.edu.unmsm.lsdfms.index.IndexEngine;
import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifica que el archivo sea recorrible sin truncamientos, que los contadores de
 * Metadata coincidan con su contenido, que cada clave indexada resuelva al offset
 * correcto y que tras PACK() no queden registros marcados.
 */
public final class IntegrityVerifier {

    public static final class Report {
        private final List<String> problems = new ArrayList<>();
        private final List<String> checks = new ArrayList<>();

        void problem(String message) { problems.add(message); }
        void ok(String message) { checks.add(message); }

        public boolean isValid() { return problems.isEmpty(); }
        public List<String> getProblems() { return List.copyOf(problems); }
        public List<String> getChecks() { return List.copyOf(checks); }

        public void print() {
            for (String check : checks) {
                System.out.println("  [OK]    " + check);
            }
            for (String problem : problems) {
                System.out.println("  [FALLO] " + problem);
            }
        }
    }

    private IntegrityVerifier() {}

    /** indexEngine puede ser null para omitir las comprobaciones de indice. */
    public static Report verify(StorageEngine storage, IndexEngine indexEngine,
                                boolean expectNoDeleted) throws IOException {
        Report report = new Report();
        Metadata metadata = storage.getMetadata();
        Path dataFile = Path.of(metadata.getActiveDataFile()).toAbsolutePath().normalize();

        if (!Files.isRegularFile(dataFile)) {
            report.problem("El archivo de datos activo no existe: " + dataFile);
            return report;
        }

        long total = 0;
        long active = 0;
        long deleted = 0;
        List<Long> activeOffsets = new ArrayList<>();

        try (RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "r")) {
            long length = raf.length();
            long offset = 0;
            while (offset < length) {
                if (length - offset < 5) {
                    report.problem("Cabecera truncada en offset " + offset);
                    return report;
                }
                raf.seek(offset);
                int flag = raf.readUnsignedByte();
                int recordLength = raf.readInt();
                if (flag != 0x00 && flag != 0x01) {
                    report.problem("Bandera de borrado invalida (" + flag + ") en offset " + offset);
                    return report;
                }
                long next = offset + 5L + recordLength;
                if (recordLength < 26 || next > length) {
                    report.problem("Longitud de registro invalida en offset " + offset + ": " + recordLength);
                    return report;
                }
                total++;
                if (flag == 0x00) {
                    active++;
                    if (activeOffsets.size() < 2000) activeOffsets.add(offset);
                } else {
                    deleted++;
                }
                offset = next;
            }
            report.ok("Archivo de datos recorrido sin truncamientos: " + total + " registros, "
                    + Files.size(dataFile) + " bytes");

            if (metadata.getTotalRecords() != total) {
                report.problem("Metadata.totalRecords=" + metadata.getTotalRecords()
                        + " no coincide con los " + total + " registros del archivo");
            }
            if (metadata.getActiveRecords() != active) {
                report.problem("Metadata.activeRecords=" + metadata.getActiveRecords()
                        + " no coincide con los " + active + " activos del archivo");
            }
            if (metadata.getDeletedRecords() != deleted) {
                report.problem("Metadata.deletedRecords=" + metadata.getDeletedRecords()
                        + " no coincide con los " + deleted + " marcados del archivo");
            }
            if (metadata.getLastOffset() != length) {
                report.problem("Metadata.lastOffset=" + metadata.getLastOffset()
                        + " no coincide con el EOF real " + length);
            }
            if (report.isValid()) {
                report.ok("Contadores de Metadata coherentes (activos=" + active
                        + ", marcados=" + deleted + ")");
            }
        }

        if (expectNoDeleted) {
            if (deleted > 0) {
                report.problem("Tras PACK() quedan " + deleted + " registros marcados para borrado");
            } else {
                report.ok("Tras PACK() no queda ningun registro marcado para borrado");
            }
        }

        if (indexEngine != null) {
            verifyIndexes(storage, indexEngine, activeOffsets, report);
        }

        return report;
    }

    /** Un indice con offsets obsoletos se delata aqui. */
    private static void verifyIndexes(StorageEngine storage, IndexEngine indexEngine,
                                      List<Long> activeOffsets, Report report) throws IOException {
        List<String> columns = indexEngine.getIndexedColumns();
        if (columns.isEmpty()) {
            report.problem("No hay ningun archivo de indice disponible");
            return;
        }

        int sampleSize = Math.min(100, activeOffsets.size());
        if (sampleSize == 0) {
            report.ok("No hay registros activos que verificar contra los indices");
            return;
        }

        int step = Math.max(1, activeOffsets.size() / sampleSize);
        int verified = 0;
        int mismatches = 0;

        for (int i = 0; i < activeOffsets.size() && verified < sampleSize; i += step) {
            long offset = activeOffsets.get(i);
            InsurancePolicy policy;
            try {
                policy = storage.readAt(offset);
            } catch (IOException e) {
                report.problem("No se pudo leer el registro activo en offset " + offset
                        + ": " + e.getMessage());
                mismatches++;
                continue;
            }
            verified++;

            for (String column : columns) {
                Object key = keyFor(column, policy);
                if (key == null) continue;
                long found = indexEngine.searchOffset(column, key);
                if (found < 0) {
                    report.problem("El indice de '" + column + "' no encuentra la clave "
                            + key + " del registro en offset " + offset);
                    mismatches++;
                    break;
                }
                InsurancePolicy resolved = storage.readAt(found);
                if (!key.toString().equals(String.valueOf(keyFor(column, resolved)))) {
                    report.problem("El indice de '" + column + "' resolvio la clave " + key
                            + " al offset " + found + ", que contiene otra clave");
                    mismatches++;
                    break;
                }
            }
        }

        if (mismatches == 0) {
            report.ok("Integridad referencial verificada en " + verified
                    + " registros x " + columns.size() + " indices (" + String.join(", ", columns) + ")");
        }
    }

    private static Object keyFor(String column, InsurancePolicy policy) {
        return switch (column) {
            case "id" -> policy.getId();
            case "dni" -> policy.getDni();
            case "departamento" -> policy.getDepartamento();
            case "tipoSeguro" -> policy.getTipoSeguro();
            case "titular" -> policy.getTitular();
            default -> null;
        };
    }
}
