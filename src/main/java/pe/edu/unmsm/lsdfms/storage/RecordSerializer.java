package pe.edu.unmsm.lsdfms.storage;

import java.io.IOException;

/**
 * Interfaz de serialización binaria nativa.
 * Convierte objetos de dominio en secuencias de bytes y viceversa.
 */
public interface RecordSerializer<T> {
    /**
     * Serializa solo los campos del payload (excluyendo el flag de borrado).
     */
    byte[] serialize(T record) throws IOException;

    /**
     * Deserializa los bytes del payload para reconstruir el registro.
     */
    T deserialize(byte[] payload, boolean deleted) throws IOException;
}
