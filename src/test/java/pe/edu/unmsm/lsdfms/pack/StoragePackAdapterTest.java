package pe.edu.unmsm.lsdfms.pack;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.RafStorageEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Verifica la publicacion del archivo compactado sobre el motor de almacenamiento. */
public final class StoragePackAdapterTest {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pack-adapter-test-");
        try {
            testCompactaYReconstruyeMetadata(root);
            testArchivoVacioNoFalla(root);
            System.out.println("All StoragePackAdapter tests passed.");
        } finally {
            removeTree(root);
        }
    }

    /** Tras publicar el compactado, los activos siguen legibles en sus offsets nuevos. */
    private static void testCompactaYReconstruyeMetadata(Path root) throws Exception {
        Path data = root.resolve("adapter.dat");
        Path meta = root.resolve("adapter.properties");
        Files.deleteIfExists(data);
        Files.deleteIfExists(meta);

        List<String> vivos = new ArrayList<>();
        long tamanoOriginal;

        try (RafStorageEngine storage = new RafStorageEngine(data.toFile(), meta.toFile())) {
            List<Long> offsets = new ArrayList<>();
            for (int i = 1; i <= 40; i++) {
                offsets.add(storage.insert(poliza(i)));
            }
            // Marca uno de cada dos: PACK debe descartar exactamente 20.
            for (int i = 0; i < offsets.size(); i += 2) {
                check(storage.markAsDeleted(offsets.get(i)), "no se pudo marcar el offset " + offsets.get(i));
            }
            for (int i = 1; i < offsets.size(); i += 2) {
                vivos.add(storage.readAt(offsets.get(i)).getDni());
            }
            tamanoOriginal = storage.getFileSize();

            Metadata antes = storage.getMetadata();
            check(antes.getTotalRecords() == 40, "totalRecords esperado 40, fue " + antes.getTotalRecords());
            check(antes.getDeletedRecords() == 20, "deletedRecords esperado 20, fue " + antes.getDeletedRecords());

            long conservados = new StoragePackAdapter().pack(storage, 4);
            check(conservados == 20, "PACK debio conservar 20 registros, conservo " + conservados);

            Metadata despues = storage.getMetadata();
            check(despues.getTotalRecords() == 20, "totalRecords tras PACK esperado 20, fue " + despues.getTotalRecords());
            check(despues.getActiveRecords() == 20, "activeRecords tras PACK esperado 20, fue " + despues.getActiveRecords());
            check(despues.getDeletedRecords() == 0, "tras PACK no deben quedar marcados, quedaron " + despues.getDeletedRecords());
            check(!despues.isIndexSynced(), "PACK debe dejar los indices desactualizados");
            check(storage.getFileSize() < tamanoOriginal, "el archivo compactado no se redujo");
            check(despues.getLastOffset() == storage.getFileSize(), "lastOffset no coincide con el EOF real");

            // Los offsets cambiaron: se recorre el archivo nuevo y se comparan los DNI.
            List<String> tras = dniEnOrden(storage, data);
            check(tras.equals(vivos), "los registros activos no sobrevivieron intactos al PACK");

            check(!Files.exists(data.resolveSibling(data.getFileName() + ".packed")),
                    "el archivo temporal .packed no se limpio");

            // El motor sigue operativo despues de reabrir el descriptor.
            long nuevo = storage.insert(poliza(999));
            check(storage.readAt(nuevo).getId() == 999, "no se puede insertar tras la compactacion");
        }

        // La metadata persistida se relee coherente en una sesion nueva.
        try (RafStorageEngine reabierto = new RafStorageEngine(data.toFile(), meta.toFile())) {
            check(reabierto.getMetadata().getTotalRecords() == 21,
                    "la metadata persistida no refleja el archivo compactado");
        }
    }

    /** Sin archivo de datos no hay nada que compactar y no debe lanzarse excepcion. */
    private static void testArchivoVacioNoFalla(Path root) throws Exception {
        Path data = root.resolve("vacio.dat");
        Path meta = root.resolve("vacio.properties");
        Files.deleteIfExists(data);
        Files.deleteIfExists(meta);

        try (RafStorageEngine storage = new RafStorageEngine(data.toFile(), meta.toFile())) {
            long conservados = new StoragePackAdapter().pack(storage, 4);
            check(conservados == 0, "un archivo vacio debe conservar 0 registros, devolvio " + conservados);
        }
    }

    private static List<String> dniEnOrden(RafStorageEngine storage, Path data) throws IOException {
        List<String> dni = new ArrayList<>();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(data.toFile(), "r")) {
            long length = raf.length();
            long offset = 0;
            while (offset < length) {
                raf.seek(offset);
                int flag = raf.readUnsignedByte();
                int recordLength = raf.readInt();
                check(flag == 0x00, "quedo un registro marcado en offset " + offset);
                dni.add(storage.readAt(offset).getDni());
                offset += 5L + recordLength;
            }
        }
        return dni;
    }

    private static InsurancePolicy poliza(int i) {
        return new InsurancePolicy(i, String.format("%08d", 40000000 + i),
                "Titular numero " + i, "SOAT", "Lima", 100.0 + i, "2026-01-01");
    }

    private static void removeTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
