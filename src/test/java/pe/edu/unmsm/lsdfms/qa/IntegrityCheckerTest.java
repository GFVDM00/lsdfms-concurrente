package pe.edu.unmsm.lsdfms.qa;

import java.nio.file.Files;
import java.nio.file.Path;

/** Prueba ejecutable del ciclo completo de QA con pocos registros. */
public final class IntegrityCheckerTest {

    private IntegrityCheckerTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("qa-test-");
        try {
            StressTest.run(5_000, 1, 11, root);
            System.out.println("All IntegrityChecker tests passed.");
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
