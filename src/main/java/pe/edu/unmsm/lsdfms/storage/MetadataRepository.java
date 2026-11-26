package pe.edu.unmsm.lsdfms.storage;

import java.io.File;
import java.io.IOException;

/**
 * Repositorio de persistencia de Metadata en disco.
 * 
 * RESPONSABLE: Alejandro (Módulo 1)
 * REQUERIMIENTOS: RF-01, RNF-01
 */
public class MetadataRepository {
    private final File metadataFile;

    public MetadataRepository(File metadataFile) {
        this.metadataFile = metadataFile;
    }

    public synchronized Metadata load() throws IOException {
        // TODO: Alejandro -> Cargar la metadata desde metadataFile o inicializar valores por defecto
        return new Metadata();
    }

    public synchronized void save(Metadata meta) throws IOException {
        // TODO: Alejandro -> Guardar el estado de metadata en metadataFile sin usar librerías externas
    }
}
