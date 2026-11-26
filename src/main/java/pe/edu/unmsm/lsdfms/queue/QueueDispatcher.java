package pe.edu.unmsm.lsdfms.queue;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Despachador de peticiones en segundo plano (Consumidor de la cola sincronizada).
 * Procesa secuencialmente las mutaciones sobre el StorageEngine para garantizar
 * consistencia y evitar carreras de escritura (RNF-02).
 */
public class QueueDispatcher implements Runnable {

    private final ThreadSafeRequestQueue queue;
    private final StorageEngine storageEngine;
    private final AtomicBoolean running;
    private Thread workerThread;

    public QueueDispatcher(ThreadSafeRequestQueue queue, StorageEngine storageEngine) {
        this.queue = queue;
        this.storageEngine = storageEngine;
        this.running = new AtomicBoolean(false);
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
                    // Para actualizaciones: borrado logico del anterior + insert append con nuevo offset
                    long oldOffset = command.getTargetOffset();
                    storageEngine.markAsDeleted(oldOffset);
                    InsurancePolicy updatedPolicy = (InsurancePolicy) command.getPayload();
                    long newOffset = storageEngine.insert(updatedPolicy);
                    command.complete(ResponseResult.ok("Registro actualizado exitosamente", newOffset, updatedPolicy));
                }
                case MAINTENANCE_PACK -> {
                    // Notificacion de mantenimiento coordinado con Anderson (PACK)
                    command.complete(ResponseResult.ok("Compactacion completada y coordinada", -1, null));
                }
                case MAINTENANCE_INDEX -> {
                    // Notificacion de mantenimiento coordinado con Jerson (INDEX)
                    command.complete(ResponseResult.ok("Reindexacion completada y coordinada", -1, null));
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
