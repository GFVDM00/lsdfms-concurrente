package pe.edu.unmsm.lsdfms.benchmark;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Compactacion secuencial de referencia para benchmark.
 *
 * <p>Recorre el archivo una vez y copia integramente los registros activos
 * (flag 0x00 + longitud + payload). No reemplaza al motor paralelo de
 * Anderson: existe solo para obtener el tiempo T1 y calcular Speedup.</p>
 */
public final class SequentialPackEngine {

    /**
     * Compacta {@code source} en {@code output}. El archivo de origen nunca se
     * modifica.
     */
    public SequentialPackResult pack(Path source, Path output)
            throws IOException, InterruptedException {
        Path input = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        Path destination = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new IOException("Archivo de entrada inexistente: " + input);
        }
        if (input.equals(destination)) {
            throw new IllegalArgumentException("La salida debe ser distinta del origen");
        }
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        long started = System.nanoTime();
        long originalSize = Files.size(input);
        long recordsRead = 0;
        long active = 0;
        long deleted = 0;

        try (RandomAccessFile file = new RandomAccessFile(input.toFile(), "r");
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(destination), 1 << 20))) {
            long length = file.length();
            long offset = 0;
            while (offset < length) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("PACK secuencial interrumpido");
                }
                if (length - offset < 5) {
                    throw new IOException("Cabecera truncada en offset " + offset);
                }
                file.seek(offset);
                int flag = file.readUnsignedByte();
                int recordLength = file.readInt();
                long next = offset + 5L + recordLength;
                if (flag != 0x00 && flag != 0x01) {
                    throw new IOException("Bandera invalida " + flag + " en offset " + offset);
                }
                if (recordLength < 26 || next < offset || next > length) {
                    throw new IOException("Registro truncado o longitud invalida en offset " + offset);
                }
                if (flag == 0x00) {
                    byte[] payload = new byte[recordLength];
                    file.readFully(payload);
                    out.writeByte(0x00);
                    out.writeInt(recordLength);
                    out.write(payload);
                    active++;
                } else {
                    deleted++;
                }
                recordsRead++;
                offset = next;
            }
            out.flush();
        }

        if (Files.size(input) != originalSize) {
            throw new IOException("El archivo de origen cambio durante el PACK secuencial");
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        return new SequentialPackResult(recordsRead, active, deleted, originalSize,
                Files.size(destination), elapsedMillis, input, destination);
    }
}
