package pe.edu.unmsm.lsdfms.qa;

import pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator;
import pe.edu.unmsm.lsdfms.index.ParallelIndexEngine;
import pe.edu.unmsm.lsdfms.pack.StoragePackAdapter;
import pe.edu.unmsm.lsdfms.storage.RafStorageEngine;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Somete el repositorio a ciclos de borrado logico, PACK e INDEX, verificando la
 * integridad referencial tras cada etapa.
 *
 *   java -cp bin pe.edu.unmsm.lsdfms.qa.QaSuite [registros] [ciclos]
 */
public final class QaSuite {

    private static final int PACK_THREADS = 4;

    public static void main(String[] args) throws Exception {
        long records = args.length > 0 ? Long.parseLong(args[0]) : 20_000L;
        int cycles = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        Path workspace = Path.of("data", "qa").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Path dataFile = workspace.resolve("qa_data.dat");
        Path metaFile = workspace.resolve("qa_metadata.properties");
        Files.deleteIfExists(metaFile);

        System.out.println("==================================================================");
        System.out.println("  SUITE DE QA - INTEGRIDAD REFERENCIAL (Modulo 6, Arancivia)      ");
        System.out.println("==================================================================");
        System.out.printf("Dataset inicial : %,d registros%n", records);
        System.out.printf("Ciclos          : %d (borrado logico -> PACK -> INDEX -> verificacion)%n%n", cycles);

        MassiveDataGenerator.Result generated =
                MassiveDataGenerator.generate(dataFile, records, 0.0, 777L);
        System.out.printf("Dataset generado: %,d registros | %.2f MB%n%n",
                generated.getRecords(), generated.getBytes() / (1024.0 * 1024.0));

        int failures = 0;
        try (RafStorageEngine storage = new RafStorageEngine(dataFile.toFile(), metaFile.toFile())) {
            ParallelIndexEngine indexEngine = new ParallelIndexEngine(workspace, 4);
            StoragePackAdapter packEngine = new StoragePackAdapter();

            indexEngine.rebuildAllIndexes(storage);
            storage.getMetadata().markIndexesSynchronized();
            storage.saveMetadata();

            System.out.println("Estado inicial:");
            failures += run(IntegrityVerifier.verify(storage, indexEngine, false));

            Random random = new Random(20261126L);
            for (int cycle = 1; cycle <= cycles; cycle++) {
                System.out.printf("%n--- Ciclo %d de %d ---%n", cycle, cycles);

                int marked = markRandomRecords(storage, random);
                System.out.printf("Marcados para borrado logico: %,d%n", marked);
                failures += run(IntegrityVerifier.verify(storage, indexEngine, false));

                long kept = packEngine.pack(storage, PACK_THREADS);
                System.out.printf("PACK() con %d hilos: %,d registros conservados%n", PACK_THREADS, kept);

                if (storage.getMetadata().isIndexSynced()) {
                    System.out.println("  [FALLO] PACK() dejo los indices marcados como sincronizados");
                    failures++;
                }

                indexEngine.rebuildAllIndexes(storage);
                storage.getMetadata().markIndexesSynchronized();
                storage.saveMetadata();
                System.out.println("INDEX() reconstruido y metadata sincronizada");

                failures += run(IntegrityVerifier.verify(storage, indexEngine, true));
            }
        }

        System.out.println();
        System.out.println("==================================================================");
        if (failures == 0) {
            System.out.println("  RESULTADO: INTEGRIDAD VERIFICADA EN TODOS LOS CICLOS");
            System.out.println("==================================================================");
        } else {
            System.out.println("  RESULTADO: " + failures + " VERIFICACION(ES) FALLIDA(S)");
            System.out.println("==================================================================");
            System.exit(1);
        }
    }

    private static int run(IntegrityVerifier.Report report) {
        report.print();
        return report.getProblems().size();
    }

    /** Marca ~20% de los registros activos. */
    private static int markRandomRecords(StorageEngine storage, Random random) throws IOException {
        List<Long> offsets = activeOffsets(storage);
        int target = Math.max(1, offsets.size() / 5);
        int marked = 0;
        for (int i = 0; i < target && !offsets.isEmpty(); i++) {
            long offset = offsets.remove(random.nextInt(offsets.size()));
            if (storage.markAsDeleted(offset)) marked++;
        }
        storage.saveMetadata();
        return marked;
    }

    private static List<Long> activeOffsets(StorageEngine storage) throws IOException {
        Path dataFile = Path.of(storage.getMetadata().getActiveDataFile());
        List<Long> offsets = new ArrayList<>();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dataFile.toFile(), "r")) {
            long length = raf.length();
            long offset = 0;
            while (offset < length) {
                raf.seek(offset);
                int flag = raf.readUnsignedByte();
                int recordLength = raf.readInt();
                if (flag == 0x00) offsets.add(offset);
                offset += 5L + recordLength;
            }
        }
        return offsets;
    }

    private QaSuite() {}
}
