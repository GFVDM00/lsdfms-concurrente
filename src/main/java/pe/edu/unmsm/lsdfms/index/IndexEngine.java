package pe.edu.unmsm.lsdfms.index;

import pe.edu.unmsm.lsdfms.storage.StorageEngine;
import java.io.IOException;
import java.util.List;

/**
 * Contrato de integracion para la Operacion Paralela INDEX() y Busqueda Dicotomica.
 * Asignado a: Jerson (Modulo 4 - Paralelismo a Nivel de Tareas).
 */
public interface IndexEngine {

    /**
     * Reconstruye en paralelo los archivos de indice (.idx) asignando un hilo por columna.
     * @param storage Motor de almacenamiento activo.
     */
    void rebuildAllIndexes(StorageEngine storage) throws IOException, InterruptedException;

    /**
     * Realiza una busqueda binaria en tiempo O(log n) sobre el indice de la columna dada.
     * @param column Nombre de la columna (ej. "dni", "id", "departamento").
     * @param key Valor a buscar.
     * @return Offset fisico exacto en el archivo de datos, o -1 si no se encuentra.
     */
    long searchOffset(String column, Object key) throws IOException;

    /**
     * Retorna la lista de columnas que cuentan con indice activo.
     */
    List<String> getIndexedColumns();
}
