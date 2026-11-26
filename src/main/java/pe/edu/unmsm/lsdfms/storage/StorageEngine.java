package pe.edu.unmsm.lsdfms.storage;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;

import java.io.IOException;

/**
 * Contrato principal del Motor de Almacenamiento LSDFMS (Alejandro).
 * Define las operaciones fundamentales sobre el archivo físico de datos.
 */
public interface StorageEngine extends AutoCloseable {

    /**
     * Escribe un registro de longitud variable al final del archivo físico.
     * @param record Registro a persistir.
     * @return El offset exacto (en bytes) donde inicia el registro en el archivo.
     */
    long insert(InsurancePolicy record) throws IOException;

    /**
     * Lee un registro en la posición física exacta dada por el offset.
     * @param offset Posición física del registro.
     * @return El registro reconstruido.
     */
    InsurancePolicy readAt(long offset) throws IOException;

    /**
     * Marca lógicamente el registro como eliminado escribiendo 0x01 en su primer byte.
     * Operación O(1) directa sin reescribir el resto del archivo.
     * @param offset Posición física del registro a eliminar.
     * @return true si la operación se ejecutó correctamente.
     */
    boolean markAsDeleted(long offset) throws IOException;

    /**
     * Retorna el tamaño actual del archivo de datos en bytes.
     */
    long getFileSize() throws IOException;

    /**
     * Retorna una vista coherente de la metadata. Las marcas de sincronización
     * hechas sobre la vista se reflejan en el motor; saveMetadata() las persiste.
     */
    Metadata getMetadata();

    /**
     * Sincroniza y guarda el estado actual de la metadata en disco.
     */
    void saveMetadata() throws IOException;

    /**
     * Publica el archivo compactado por PACK() como nuevo archivo de datos activo,
     * reconstruyendo offsets y contadores y dejando los índices desactualizados.
     *
     * Solo debe invocarse desde el hilo que serializa las mutaciones.
     */
    default void replaceDataFile(java.nio.file.Path compactedFile) throws IOException {
        throw new UnsupportedOperationException(
                "Este motor de almacenamiento no soporta la publicación de archivos compactados");
    }
}
