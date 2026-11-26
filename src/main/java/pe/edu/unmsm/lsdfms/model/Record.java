package pe.edu.unmsm.lsdfms.model;

/**
 * Interfaz base para cualquier registro gestionado por el motor LSDFMS.
 */
public interface Record {
    long getId();
    boolean isDeleted();
    void setDeleted(boolean deleted);
}
