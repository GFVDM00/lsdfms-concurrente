package pe.edu.unmsm.lsdfms.pack;

import pe.edu.unmsm.lsdfms.storage.StorageEngine;
import java.io.IOException;

/**
 * Contrato de integracion para la Operacion Paralela PACK() (Compactacion).
 * Asignado a: Anderson (Modulo 3 - Paralelismo a Nivel de Datos).
 */
public interface PackEngine {

    /**
     * Ejecuta el proceso de compactacion fisica eliminando registros marcados (0x01).
     * @param storage Motor de almacenamiento activo.
     * @param numThreads Numero de hilos paralelos de trabajo (2, 4 u 8).
     * @return Numero de registros compactados limpios en el nuevo archivo.
     */
    long pack(StorageEngine storage, int numThreads) throws IOException, InterruptedException;
}
