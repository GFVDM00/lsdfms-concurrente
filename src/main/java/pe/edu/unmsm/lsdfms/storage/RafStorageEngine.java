package pe.edu.unmsm.lsdfms.storage;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;

import java.io.File;
import java.io.IOException;

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
    private Metadata metadata;

    public RafStorageEngine(File dataFile, File metadataFile) throws IOException {
        this.dataFile = dataFile;
        this.metadataFile = metadataFile;
        // TODO: Alejandro -> Inicializar el RandomAccessFile y cargar la metadata
    }

    @Override
    public long insert(InsurancePolicy record) throws IOException {
        // TODO: Alejandro -> Escribir el registro al final del archivo (seek) y retornar offset
        throw new UnsupportedOperationException("Pendiente de implementacion por Alejandro (RF-01)");
    }

    @Override
    public InsurancePolicy readAt(long offset) throws IOException {
        // TODO: Alejandro -> Posicionarse en offset (seek), leer flags, longitud y campos
        throw new UnsupportedOperationException("Pendiente de implementacion por Alejandro (RF-01)");
    }

    @Override
    public boolean markAsDeleted(long offset) throws IOException {
        // TODO: Alejandro -> Posicionarse en offset y sobrescribir el byte de borrado en O(1)
        throw new UnsupportedOperationException("Pendiente de implementacion por Alejandro (RF-01)");
    }

    @Override
    public long getFileSize() throws IOException {
        // TODO: Alejandro -> Retornar raf.length()
        throw new UnsupportedOperationException("Pendiente de implementacion por Alejandro");
    }

    @Override
    public Metadata getMetadata() {
        return this.metadata;
    }

    @Override
    public void saveMetadata() throws IOException {
        // TODO: Alejandro -> Persistir la metadata actualizada
        throw new UnsupportedOperationException("Pendiente de implementacion por Alejandro");
    }

    @Override
    public void close() throws IOException {
        // TODO: Alejandro -> Cerrar de forma segura el RandomAccessFile y guardar metadata
    }
}
