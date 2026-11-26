package pe.edu.unmsm.lsdfms.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Extension del motor de almacenamiento que permite sustituir de forma
 * coordinada el archivo activo. La usa el mantenimiento PACK: el archivo
 * compactado se publica y el motor se reabre sobre el nuevo archivo.
 */
public interface MutableStorageEngine extends StorageEngine {

    /** Ruta absoluta del archivo de datos activo. */
    Path activeDataFilePath();

    /**
     * Reemplaza el archivo activo por {@code newFile}, reabre el motor y
     * reconstruye offsets y contadores desde el nuevo archivo.
     */
    void replaceActiveDataFile(Path newFile) throws IOException;
}
