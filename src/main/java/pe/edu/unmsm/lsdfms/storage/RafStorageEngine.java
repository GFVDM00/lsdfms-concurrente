package pe.edu.unmsm.lsdfms.storage;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Implementación del Motor de Almacenamiento basada en RandomAccessFile.
 * 
 * RESPONSABLE: Alejandro (Módulo 1: Integración General, Motor LSDFMS y Metadata)
 * REQUERIMIENTOS: RF-01, RNF-01, RNF-02
 *
 * Tareas a implementar por Alejandro:
 * 1. Inicializar RandomAccessFile en modo "rw" con el archivo dataFile.
 * 2. Implementar inserción de registros de longitud variable con offsets exactos (seek()).
 * 3. Implementar lectura por offset exacto deserializando campos de longitud variable.
 * 4. Implementar marcado de borrado lógico (0x01) en O(1) modificando el byte de cabecera.
 * 5. Gestionar la sincronización de Metadata (MetadataRepository).
 */
public class RafStorageEngine implements StorageEngine {

    private final File dataFile;
    private final File metadataFile;
    private final MetadataRepository metadataRepository;
    private final RandomAccessFile raf;
    private final Set<Long> recordOffsets = new HashSet<>();
    private final ThreadLocal<Metadata> metadataView = new ThreadLocal<>();
    private Metadata metadata;

    public RafStorageEngine(File dataFile, File metadataFile) throws IOException {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile no puede ser null");
        this.metadataFile = Objects.requireNonNull(metadataFile, "metadataFile no puede ser null");
        File parent = dataFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("No se pudo crear el directorio de datos: " + parent);
        }
        this.metadataRepository = new MetadataRepository(metadataFile);
        this.metadata = metadataRepository.load();
        String previousDataFile = metadata.getActiveDataFile();
        this.metadata.setActiveDataFile(dataFile.getPath());
        this.raf = new RandomAccessFile(dataFile, "rw");
        try {
            rebuildRecordBoundaries(previousDataFile);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    @Override
    public synchronized long insert(InsurancePolicy record) throws IOException {
        Objects.requireNonNull(record, "record no puede ser null");
        byte[] payload = serialize(record);
        long offset = raf.length();
        Metadata before = metadata.snapshot();
        try {
            raf.seek(offset);
            raf.writeByte(0x00);
            raf.writeInt(payload.length);
            raf.write(payload);
            recordOffsets.add(offset);
            metadata.recordInserted(raf.getFilePointer());
            metadataRepository.save(metadata);
            return offset;
        } catch (IOException | RuntimeException failure) {
            recordOffsets.remove(offset);
            metadata.restore(before);
            try {
                raf.setLength(offset);
                raf.seek(offset);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    @Override
    public synchronized InsurancePolicy readAt(long offset) throws IOException {
        if (!recordOffsets.contains(offset)) {
            throw new IOException("Offset fuera del archivo: " + offset);
        }
        raf.seek(offset);
        byte deletedFlag = raf.readByte();
        if (deletedFlag != 0x00 && deletedFlag != 0x01) {
            throw new IOException("Bandera de borrado no válida en offset " + offset);
        }
        int recordLength = raf.readInt();
        if (recordLength < 0 || recordLength > raf.length() - raf.getFilePointer()) {
            throw new IOException("Longitud de registro no válida en offset " + offset);
        }
        byte[] payload = new byte[recordLength];
        raf.readFully(payload);
        return deserialize(payload, deletedFlag == 0x01);
    }

    @Override
    public synchronized boolean markAsDeleted(long offset) throws IOException {
        if (!recordOffsets.contains(offset)) {
            return false;
        }
        raf.seek(offset);
        int deletedFlag = raf.readUnsignedByte();
        if (deletedFlag == 0x01) {
            return false;
        }
        if (deletedFlag != 0x00) {
            throw new IOException("Bandera de borrado no válida en offset " + offset);
        }
        // Sobrescribe solo el byte de estado; el cuerpo y el tamaño no se modifican.
        Metadata before = metadata.snapshot();
        try {
            raf.seek(offset);
            raf.writeByte(0x01);
            metadata.recordDeleted();
            metadataRepository.save(metadata);
            return true;
        } catch (IOException | RuntimeException failure) {
            metadata.restore(before);
            try {
                raf.seek(offset);
                raf.writeByte(0x00);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    @Override
    public long getFileSize() throws IOException {
        synchronized (this) {
            return raf.length();
        }
    }

    @Override
    public synchronized Metadata getMetadata() {
        Metadata view = this.metadata.snapshot();
        view.setIndexSyncListener(() -> publishIndexSyncStatus(view));
        metadataView.set(view);
        return view;
    }

    private synchronized void publishIndexSyncStatus(Metadata view) {
        if (view.hasIndexSyncChanges()) {
            synchronized (metadata) {
                metadata.applyIndexSyncChanges(view);
            }
        }
    }

    @Override
    public void saveMetadata() throws IOException {
        synchronized (this) {
            Metadata view = metadataView.get();
            if (view != null && view.hasIndexSyncChanges()) {
                synchronized (metadata) {
                    // Aplica solo campos modificados explícitamente en esta vista. Nunca
                    // restaura contadores o campos que pudieron cambiar después del snapshot.
                    metadata.applyIndexSyncChanges(view);
                }
            }
            metadataView.remove();
            metadataRepository.save(metadata);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            try {
                metadataRepository.save(metadata);
            } finally {
                raf.close();
            }
        }
    }

    /**
     * Reconstruye los límites y contadores desde el archivo, que es la fuente de verdad.
     * Mantener los offsets permite que las lecturas sean directas y el borrado O(1).
     */
    private void rebuildRecordBoundaries(String previousDataFile) throws IOException {
        long fileLength = raf.length();
        long oldTotal = metadata.getTotalRecords();
        long oldActive = metadata.getActiveRecords();
        long oldDeleted = metadata.getDeletedRecords();
        long oldOffset = metadata.getLastOffset();
        long offset = 0;
        long active = 0;
        long deleted = 0;
        while (offset < fileLength) {
            if (fileLength - offset < 5) {
                throw new IOException("Cabecera de registro truncada en offset " + offset);
            }
            raf.seek(offset);
            int flag = raf.readUnsignedByte();
            if (flag != 0x00 && flag != 0x01) {
                throw new IOException("Bandera de borrado no válida en offset " + offset);
            }
            int recordLength = raf.readInt();
            long nextOffset = offset + 5L + recordLength;
            if (recordLength < 26 || nextOffset < offset || nextOffset > fileLength) {
                throw new IOException("Registro truncado o longitud no válida en offset " + offset);
            }
            recordOffsets.add(offset);
            if (flag == 0x00) {
                active++;
            } else {
                deleted++;
            }
            offset = nextOffset;
        }

        if (!dataFile.getPath().equals(previousDataFile)
                || oldTotal != recordOffsets.size() || oldActive != active
                || oldDeleted != deleted || oldOffset != fileLength) {
            metadata.setIndexSynced(false);
        }
        metadata.setTotalRecords(recordOffsets.size());
        metadata.setActiveRecords(active);
        metadata.setDeletedRecords(deleted);
        // lastOffset representa EOF: el offset donde comenzaría la próxima inserción.
        metadata.setLastOffset(fileLength);
        metadataRepository.save(metadata);
    }

    private static byte[] serialize(InsurancePolicy record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
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

    private static InsurancePolicy deserialize(byte[] payload, boolean deleted)
            throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long id = input.readLong();
            String dni = readString(input);
            String titular = readString(input);
            String tipoSeguro = readString(input);
            String departamento = readString(input);
            double prima = input.readDouble();
            String fechaEmision = readString(input);
            if (input.available() != 0) {
                throw new IOException("El payload contiene bytes sobrantes");
            }
            return new InsurancePolicy(
                    deleted, id, dni, titular, tipoSeguro, departamento, prima, fechaEmision);
        } catch (java.io.EOFException e) {
            throw new IOException("Payload de póliza incompleto", e);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        Objects.requireNonNull(value, "Los campos de texto no pueden ser null");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 0xffff) {
            throw new IOException("Campo de texto supera la longitud máxima de "
                    + 0xffff + " bytes");
        }
        output.writeShort(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > input.available()) {
            throw new IOException("Longitud de campo de texto no válida: " + length);
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }
}
