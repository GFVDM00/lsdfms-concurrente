package pe.edu.unmsm.lsdfms.benchmark;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Configuracion de una corrida de benchmarking. */
public final class BenchmarkConfig {

    public enum Operation { PACK, INDEX }

    private final Path dataFile;
    private final Path metadataFile;
    private final Path csvFile;
    private final List<Operation> operations;
    private final List<Integer> threads;
    private final int repetitions;
    private final int warmup;

    public BenchmarkConfig(Path dataFile, Path metadataFile, Path csvFile,
                           List<Operation> operations, List<Integer> threads,
                           int repetitions, int warmup) {
        if (repetitions <= 0) throw new IllegalArgumentException("repetitions debe ser > 0");
        if (warmup < 0) throw new IllegalArgumentException("warmup no puede ser negativo");
        if (operations.isEmpty()) throw new IllegalArgumentException("Debe indicar al menos una operacion");
        for (int value : threads) {
            if (value != 1 && value != 2 && value != 4 && value != 8) {
                throw new IllegalArgumentException("Los hilos permitidos son 1, 2, 4 u 8: " + value);
            }
        }
        this.dataFile = dataFile.toAbsolutePath().normalize();
        this.metadataFile = metadataFile.toAbsolutePath().normalize();
        this.csvFile = csvFile.toAbsolutePath().normalize();
        this.operations = List.copyOf(operations);
        this.threads = List.copyOf(threads);
        this.repetitions = repetitions;
        this.warmup = warmup;
    }

    public Path dataFile() { return dataFile; }
    public Path metadataFile() { return metadataFile; }
    public Path csvFile() { return csvFile; }
    public List<Operation> operations() { return operations; }
    public List<Integer> threads() { return threads; }
    public int repetitions() { return repetitions; }
    public int warmup() { return warmup; }

    public static BenchmarkConfig fromArgs(String[] args) {
        Path data = null;
        Path metadata = null;
        Path csv = Path.of("results", "benchmark.csv");
        List<Operation> operations = new ArrayList<>(List.of(Operation.values()));
        List<Integer> threads = List.of(1, 2, 4, 8);
        int repetitions = 3;
        int warmup = 1;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--data" -> data = Path.of(value(args, ++i, arg));
                case "--metadata" -> metadata = Path.of(value(args, ++i, arg));
                case "--output" -> csv = Path.of(value(args, ++i, arg));
                case "--operations" -> operations = parseOperations(value(args, ++i, arg));
                case "--threads" -> threads = parseThreads(value(args, ++i, arg));
                case "--repetitions" -> repetitions = Integer.parseInt(value(args, ++i, arg));
                case "--warmup" -> warmup = Integer.parseInt(value(args, ++i, arg));
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Opcion desconocida: " + arg);
            }
        }
        if (data == null) {
            throw new IllegalArgumentException("Falta --data PATH");
        }
        if (metadata == null) {
            String name = data.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            metadata = data.resolveSibling(base + ".properties");
        }
        return new BenchmarkConfig(data, metadata, csv, operations, threads,
                repetitions, warmup);
    }

    private static List<Operation> parseOperations(String value) {
        List<Operation> result = new ArrayList<>();
        for (String token : value.split(",")) {
            if (!token.isBlank()) {
                result.add(Operation.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            }
        }
        return result;
    }

    private static List<Integer> parseThreads(String value) {
        List<Integer> result = new ArrayList<>();
        for (String token : value.split(",")) {
            if (!token.isBlank()) {
                result.add(Integer.parseInt(token.trim()));
            }
        }
        return result;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Falta el valor de " + option);
        }
        return args[index];
    }

    public static void printHelp() {
        System.out.println("""
                Uso: BenchmarkRunner --data PATH [opciones]
                  --data PATH             Dataset .dat generado
                  --metadata PATH         Metadata .properties (default: mismo nombre)
                  --output PATH           CSV de resultados (default: results/benchmark.csv)
                  --operations LIST       pack,index (default: ambas)
                  --threads LIST          1,2,4,8 (default: 1,2,4,8)
                  --repetitions N         Repeticiones medidas (default: 3)
                  --warmup N              Corridas de calentamiento (default: 1)
                  --help                  Muestra esta ayuda
                """);
    }
}
