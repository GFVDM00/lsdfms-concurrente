package pe.edu.unmsm.lsdfms.queue;

/**
 * Tipos de operaciones de alteracion de datos y mantenimiento
 * que ingresan a la Cola Sincronizada (RF-04).
 */
public enum RequestType {
    INSERT,
    UPDATE,
    DELETE_LOGICAL,
    MAINTENANCE_PACK,
    MAINTENANCE_INDEX
}
