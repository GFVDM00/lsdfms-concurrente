package pe.edu.unmsm.lsdfms.generator;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Random;

/**
 * Generador de datasets masivos sinteticos de polizas.
 *
 * Escribe directamente el formato binario de RF-01 sin pasar por StorageEngine:
 * insertar millones de registros uno a uno con fsync de metadata seria inviable.
 * RafStorageEngine reconstruye la metadata al abrir el archivo.
 */
public final class MassiveDataGenerator {

    public static final class Result {
        private final long records;
        private final long deletedRecords;
        private final long bytes;
        private final long elapsedMillis;
        private final Path file;

        Result(long records, long deletedRecords, long bytes, long elapsedMillis, Path file) {
            this.records = records;
            this.deletedRecords = deletedRecords;
            this.bytes = bytes;
            this.elapsedMillis = elapsedMillis;
            this.file = file;
        }

        public long getRecords() { return records; }
        public long getDeletedRecords() { return deletedRecords; }
        public long getActiveRecords() { return records - deletedRecords; }
        public long getBytes() { return bytes; }
        public long getElapsedMillis() { return elapsedMillis; }
        public Path getFile() { return file; }
        public double getAverageRecordBytes() { return records == 0 ? 0 : (double) bytes / records; }
    }

    private static final String[] NOMBRES = {
        "Ana", "Luis", "María", "José", "Carmen", "Jorge", "Rosa", "Miguel", "Lucía",
        "Carlos", "Elena", "Víctor", "Sofía", "Raúl", "Patricia", "Andrés", "Gabriela",
        "Fernando", "Milagros", "Óscar", "Katherine", "Walter", "Yesenia", "Edu"
    };

    private static final String[] APELLIDOS = {
        "Quispe", "Mamani", "Flores", "Sánchez", "Huamán", "Chávez", "Rojas", "Ramos",
        "Vásquez", "Castillo", "Arancivia", "Tataje", "Fernández", "Valqui", "Ñopo",
        "De la Cruz", "Paredes Montoya", "Villanueva", "Espinoza", "Cáceres Alva"
    };

    private static final String[] TIPOS_SEGURO = {
        "SOAT", "Vida Ley", "Salud Integral", "Hogar Multiriesgo",
        "Vehicular Todo Riesgo", "Accidentes Personales"
    };

    private static final String[] DEPARTAMENTOS = {
        "Lima", "Cusco", "Arequipa", "Piura", "Ica", "Áncash", "Junín", "Loreto",
        "La Libertad", "Lambayeque", "Madre de Dios", "Huancavelica", "Cajamarca",
        "San Martín", "Tacna", "Puno", "Ayacucho", "Callao", "Ucayali", "Tumbes"
    };

    private MassiveDataGenerator() {}

    /** deletedRatio es la proporcion [0,1) marcada con la bandera 0x01. */
    public static Result generate(Path target, long records, double deletedRatio, long seed)
            throws IOException {
        if (records < 0) throw new IllegalArgumentException("records no puede ser negativo");
        if (deletedRatio < 0 || deletedRatio >= 1) {
            throw new IllegalArgumentException("deletedRatio debe estar en [0, 1)");
        }

        Path file = target.toAbsolutePath().normalize();
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);

        Random random = new Random(seed);
        long started = System.nanoTime();
        long bytes = 0;
        long deleted = 0;

        try (OutputStream raw = Files.newOutputStream(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedOutputStream buffered = new BufferedOutputStream(raw, 1 << 20);
             DataOutputStream output = new DataOutputStream(buffered)) {

            for (long i = 0; i < records; i++) {
                byte[] payload = serialize(randomPolicy(i + 1, random));
                boolean marcado = random.nextDouble() < deletedRatio;
                output.writeByte(marcado ? 0x01 : 0x00);
                output.writeInt(payload.length);
                output.write(payload);
                bytes += 5L + payload.length;
                if (marcado) deleted++;
            }
        }

        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        return new Result(records, deleted, bytes, elapsed, file);
    }

    private static InsurancePolicy randomPolicy(long id, Random random) {
        String titular = NOMBRES[random.nextInt(NOMBRES.length)] + " "
                + APELLIDOS[random.nextInt(APELLIDOS.length)] + " "
                + APELLIDOS[random.nextInt(APELLIDOS.length)];
        String dni = String.format("%08d", 10_000_000 + random.nextInt(79_999_999));
        String tipo = TIPOS_SEGURO[random.nextInt(TIPOS_SEGURO.length)];
        String departamento = DEPARTAMENTOS[random.nextInt(DEPARTAMENTOS.length)];
        double prima = Math.round((80 + random.nextDouble() * 9_920) * 100.0) / 100.0;
        LocalDate fecha = LocalDate.of(2018 + random.nextInt(9),
                1 + random.nextInt(12), 1 + random.nextInt(28));
        return new InsurancePolicy(id, dni, titular, tipo, departamento, prima, fecha.toString());
    }

    private static byte[] serialize(InsurancePolicy record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(record.getId());
            writeString(output, record.getDni());
            writeString(output, record.getTitular());
            writeString(output, record.getTipoSeguro());
            writeString(output, record.getDepartamento());
            output.writeDouble(record.getPrima());
            writeString(output, record.getFechaEmision());
        }
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 0xffff) throw new IOException("Campo de texto demasiado largo");
        output.writeShort(encoded.length);
        output.write(encoded);
    }

    public static void main(String[] args) throws IOException {
        long records = args.length > 0 ? Long.parseLong(args[0]) : 100_000L;
        Path target = Path.of(args.length > 1 ? args[1] : "data/policies_data.dat");
        double deletedRatio = args.length > 2 ? Double.parseDouble(args[2]) : 0.30;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 20261126L;

        System.out.println("==========================================================");
        System.out.println("  GENERADOR DE DATASETS MASIVOS (Modulo 6 - Arancivia)    ");
        System.out.println("==========================================================");
        System.out.printf("Registros solicitados : %,d%n", records);
        System.out.printf("Marcados para borrado : %.0f%%%n", deletedRatio * 100);
        System.out.println("Archivo destino       : " + target.toAbsolutePath().normalize());
        System.out.println("Generando...");

        Result result = generate(target, records, deletedRatio, seed);

        System.out.println("----------------------------------------------------------");
        System.out.printf("Registros escritos    : %,d%n", result.getRecords());
        System.out.printf("  activos             : %,d%n", result.getActiveRecords());
        System.out.printf("  marcados (0x01)     : %,d%n", result.getDeletedRecords());
        System.out.printf("Tamano del archivo    : %,d bytes (%.2f MB)%n",
                result.getBytes(), result.getBytes() / (1024.0 * 1024.0));
        System.out.printf("Longitud media        : %.1f bytes/registro%n", result.getAverageRecordBytes());
        System.out.printf("Tiempo de generacion  : %,d ms%n", result.getElapsedMillis());
        System.out.println("==========================================================");
    }
}
