package pe.edu.unmsm.lsdfms.generator;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.MetadataRepository;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generador masivo de datasets LSDFMS.
 *
 * <p>Escribe directamente el formato fisico documentado en
 * {@code docs/ESPECIFICACION_FORMATO_BINARIO.md}:</p>
 *
 * <pre>
 * flag (1 byte) | recordLength (4 bytes, big-endian) | payload
 * </pre>
 *
 * <p>No usa {@code RafStorageEngine.insert()} en masa porque ese metodo
 * persiste metadata en cada insercion. El binario se escribe en streaming y
 * luego se valida con {@link DatasetVerifier}.</p>
 *
 * <p>Uso:</p>
 * <pre>
 * java -cp bin pe.edu.unmsm.lsdfms.generator.MassiveDataGenerator \
 *   --records 100000 --output data/dataset_100k.dat \
 *   --metadata data/dataset_100k.properties --seed 42 --deleted-percent 10
 * </pre>
 */
public final class MassiveDataGenerator {

    private static final int BUFFER_BYTES = 1 << 20;

    private MassiveDataGenerator() {}

    public static GenerationResult generate(DatasetSpec spec) throws IOException {
        long started = System.nanoTime();
        Path output = spec.output();
        Path metadataPath = spec.metadata();
        createParent(output);
        createParent(metadataPath);
        Files.deleteIfExists(output);
        Files.deleteIfExists(metadataPath);

        PolicyFactory factory = new PolicyFactory(spec.seed());
        SplittableRandom deletionRandom = new SplittableRandom(
                spec.seed() ^ 0x9E3779B97F4A7C15L);

        long active = 0;
        long deleted = 0;
        long lastOffset = 0;
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream(256);
        DataOutputStream payloadOutput = new DataOutputStream(payloadBuffer);

        try (OutputStream fileOutput = Files.newOutputStream(output);
             BufferedOutputStream buffered = new BufferedOutputStream(fileOutput, BUFFER_BYTES);
             DataOutputStream dataOutput = new DataOutputStream(buffered)) {
            for (long index = 0; index < spec.records(); index++) {
                InsurancePolicy policy = factory.next(index);
                boolean isDeleted = spec.deletedPercent() > 0
                        && deletionRandom.nextInt(100) < spec.deletedPercent();

                payloadBuffer.reset();
                writePayload(payloadOutput, policy);
                int payloadLength = payloadBuffer.size();

                dataOutput.writeByte(isDeleted ? 0x01 : 0x00);
                dataOutput.writeInt(payloadLength);
                payloadBuffer.writeTo(dataOutput);

                lastOffset += 5L + payloadLength;
                if (isDeleted) {
                    deleted++;
                } else {
                    active++;
                }
            }
            dataOutput.flush();
        }

        Metadata metadata = new Metadata();
        metadata.setActiveDataFile(output.toString());
        metadata.setTotalRecords(spec.records());
        metadata.setActiveRecords(active);
        metadata.setDeletedRecords(deleted);
        metadata.setLastOffset(lastOffset);
        metadata.setIndexSynced(false);
        metadata.setLastCompactedAt(0L);
        metadata.setLastIndexedAt(0L);
        metadata.setIndexedColumns(List.of("id", "dni", "departamento", "tipoSeguro"));
        new MetadataRepository(metadataPath.toFile()).save(metadata);

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        return new GenerationResult(output, metadataPath, spec.records(), active,
                deleted, lastOffset, elapsedMillis);
    }

    private static void writePayload(DataOutputStream output, InsurancePolicy policy)
            throws IOException {
        output.writeLong(policy.getId());
        writeString(output, policy.getDni());
        writeString(output, policy.getTitular());
        writeString(output, policy.getTipoSeguro());
        writeString(output, policy.getDepartamento());
        output.writeDouble(policy.getPrima());
        writeString(output, policy.getFechaEmision());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encoded.length > 0xffff) {
            throw new IOException("Campo de texto demasiado largo: " + encoded.length + " bytes");
        }
        output.writeShort(encoded.length);
        output.write(encoded);
    }

    private static void createParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            if (options.help) {
                printHelp();
                return;
            }
            DatasetSpec spec = new DatasetSpec(options.records, options.output,
                    options.metadata, options.seed, options.deletedPercent);
            GenerationResult result = generate(spec);
            System.out.println("Dataset generado correctamente:");
            System.out.println("  Archivo de datos : " + result.getDataFile());
            System.out.println("  Metadata         : " + result.getMetadataFile());
            System.out.println("  Registros        : " + result.getRecords());
            System.out.println("  Activos          : " + result.getActiveRecords());
            System.out.println("  Eliminados       : " + result.getDeletedRecords());
            System.out.println("  Bytes            : " + result.getBytesWritten());
            System.out.println("  Tiempo           : " + result.getElapsedMillis() + " ms");
            System.out.printf("  Throughput       : %.0f registros/s%n",
                    result.getRecordsPerSecond());
        } catch (IllegalArgumentException e) {
            System.err.println("Error de argumentos: " + e.getMessage());
            printHelp();
            System.exit(2);
        } catch (IOException e) {
            System.err.println("Error generando el dataset: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("""
                Uso: MassiveDataGenerator [opciones]
                  --records N            Cantidad de registros (default: 100000)
                  --output PATH          Archivo .dat de salida (default: data/dataset_<N>.dat)
                  --metadata PATH        Archivo .properties (default: mismo nombre con .properties)
                  --seed N               Semilla reproducible (default: 42)
                  --deleted-percent N    Porcentaje de registros marcados como eliminados (0-100)
                  --help                 Muestra esta ayuda
                """);
    }

    private static final class CliOptions {
        long records = 100_000;
        Path output;
        Path metadata;
        long seed = 42;
        int deletedPercent = 0;
        boolean help;

        static CliOptions parse(String[] args) {
            CliOptions options = new CliOptions();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> options.help = true;
                    case "--records" -> options.records = Long.parseLong(value(args, ++i, arg));
                    case "--output" -> options.output = Path.of(value(args, ++i, arg));
                    case "--metadata" -> options.metadata = Path.of(value(args, ++i, arg));
                    case "--seed" -> options.seed = Long.parseLong(value(args, ++i, arg));
                    case "--deleted-percent" ->
                            options.deletedPercent = Integer.parseInt(value(args, ++i, arg));
                    default -> throw new IllegalArgumentException("Opcion desconocida: " + arg);
                }
            }
            if (options.output == null) {
                options.output = Path.of("data", "dataset_" + options.records + ".dat");
            }
            if (options.metadata == null) {
                String name = options.output.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                Path parent = options.output.getParent();
                options.metadata = parent == null
                        ? Path.of(base + ".properties")
                        : parent.resolve(base + ".properties");
            }
            return options;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Falta el valor de " + option);
            }
            return args[index];
        }
    }
}
