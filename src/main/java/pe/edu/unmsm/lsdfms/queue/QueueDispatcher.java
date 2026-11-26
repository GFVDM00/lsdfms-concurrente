package pe.edu.unmsm.lsdfms.queue;

import pe.edu.unmsm.lsdfms.index.IndexEngine;
import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.pack.PackEngine;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Despachador de peticiones en segundo plano (Consumidor de la cola sincronizada).
 * Procesa secuencialmente las mutaciones sobre el StorageEngine para garantizar
 * consistencia y evitar carreras de escritura (RNF-02).
 *
 * Tambien coordina el ciclo de mantenimiento (RF-07) ejecutando PACK() e INDEX()
 * de manera sincronizada cuando Anderson y Jerson suministren sus motores.
 */
public class QueueDispatcher implements Runnable {

    private final ThreadSafeRequestQueue queue;
    private final StorageEngine storageEngine;
    private final AtomicBoolean running;

    private PackEngine packEngine;
    private IndexEngine indexEngine;

    private Thread workerThread;

    public QueueDispatcher(ThreadSafeRequestQueue queue, StorageEngine storageEngine) {
        this.queue = queue;
        this.storageEngine = storageEngine;
        this.running = new AtomicBoolean(false);
    }

    public void setPackEngine(PackEngine packEngine) {
        this.packEngine = packEngine;
    }

    public void setIndexEngine(IndexEngine indexEngine) {
        this.indexEngine = indexEngine;
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this, "QueueDispatcher-Worker");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (workerThread != null) {
                workerThread.interrupt();
            }
        }
    }

    @Override
    public void run() {
        while (running.get() || !queue.isEmpty()) {
            try {
                RequestCommand<?> command = queue.dequeue(500, TimeUnit.MILLISECONDS);
                if (command == null) {
                    continue;
                }

                processCommand(command);

            } catch (InterruptedException e) {
                if (!running.get()) {
                    break;
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[QueueDispatcher] Error no controlado: " + e.getMessage());
            }
        }
    }

    private void processCommand(RequestCommand<?> command) {
        try {
            switch (command.getType()) {
                case INSERT -> {
                    InsurancePolicy policy = (InsurancePolicy) command.getPayload();
                    long offset = storageEngine.insert(policy);
                    command.complete(ResponseResult.ok("Registro insertado exitosamente", offset, policy));
                }
                case DELETE_LOGICAL -> {
                    long offset = command.getTargetOffset();
                    boolean deleted = storageEngine.markAsDeleted(offset);
                    if (deleted) {
                        command.complete(ResponseResult.ok("Registro marcado para borrado", offset, null));
                    } else {
                        command.complete(ResponseResult.error("El registro en offset " + offset + " ya estaba eliminado o no existe"));
                    }
                }
                case UPDATE -> {
                    long oldOffset = command.getTargetOffset();
                    storageEngine.markAsDeleted(oldOffset);
                    InsurancePolicy updatedPolicy = (InsurancePolicy) command.getPayload();
                    long newOffset = storageEngine.insert(updatedPolicy);
                    command.complete(ResponseResult.ok("Registro actualizado exitosamente", newOffset, updatedPolicy));
                }
                case MAINTENANCE_PACK -> {
                    // Coordinacion RF-07: Compactacion con Anderson
                    if (packEngine != null) {
                        long activeKept = packEngine.pack(storageEngine, 4);
                        command.complete(ResponseResult.ok("PACK completado: " + activeKept + " registros activos retenidos", -1, activeKept));
                    } else {
                        // Modo coordinado preliminar
                        command.complete(ResponseResult.ok("Mantenimiento PACK coordinado (Pendiente modulo final de Anderson)", -1, null));
                    }
                }
                case MAINTENANCE_INDEX -> {
                    // Coordinacion RF-07: Reindexacion con Jerson
                    if (indexEngine != null) {
                        indexEngine.rebuildAllIndexes(storageEngine);
                        storageEngine.getMetadata().markIndexesSynchronized();
                        storageEngine.saveMetadata();
                        command.complete(ResponseResult.ok("INDEX completado: indices reconstruidos y sincronizados", -1, null));
                    } else {
                        // Sincronizar metadata
                        storageEngine.getMetadata().markIndexesSynchronized();
                        command.complete(ResponseResult.ok("Mantenimiento INDEX coordinado: indices marcados sincronizados", -1, null));
                    }
                }
                default -> command.complete(ResponseResult.error("Tipo de operacion no soportada: " + command.getType()));
            }
        } catch (Exception e) {
            command.completeExceptionally(e);
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
