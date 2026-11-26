package pe.edu.unmsm.lsdfms.queue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cola Sincronizada Concurrente (Thread-Safe Request Queue).
 * Implementada por Brandon (Módulo 2).
 *
 * Cumple con:
 *  - RF-04: Encolar y sincronizar ordenadamente peticiones de alteracion de datos.
 *  - RNF-01: Implementacion nativa en Java estandar usando ReentrantLock y Condition.
 *  - RNF-02: Exclusion mutua estricta y ausencia de race conditions (Bounded Buffer).
 */
public class ThreadSafeRequestQueue {

    private final Object[] items;
    private final int capacity;

    private int count;
    private int putIndex;
    private int takeIndex;

    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;

    // Metricas de observabilidad para el servidor y benchmarking
    private long totalEnqueuedCount = 0;
    private long totalDequeuedCount = 0;

    public ThreadSafeRequestQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("La capacidad de la cola debe ser mayor a 0");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.lock = new ReentrantLock(true); // Fair lock para respetar FIFO estricto
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    /**
     * Encola una peticion de manera bloqueante.
     * Si la cola esta llena, el hilo productor espera hasta que notFull sea senalizado.
     */
    public void enqueue(RequestCommand<?> command) throws InterruptedException {
        if (command == null) {
            throw new NullPointerException("No se pueden encolar comandos nulos");
        }

        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                notFull.await();
            }

            items[putIndex] = command;
            if (++putIndex == capacity) {
                putIndex = 0;
            }
            count++;
            totalEnqueuedCount++;

            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encola una peticion con un tiempo limite de espera.
     */
    public boolean enqueue(RequestCommand<?> command, long timeout, TimeUnit unit) throws InterruptedException {
        if (command == null) {
            throw new NullPointerException("No se pueden encolar comandos nulos");
        }

        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (nanos <= 0L) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }

            items[putIndex] = command;
            if (++putIndex == capacity) {
                putIndex = 0;
            }
            count++;
            totalEnqueuedCount++;

            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Desencola una peticion de manera bloqueante.
     * Si la cola esta vacia, el hilo consumidor espera hasta que notEmpty sea senalizado.
     */
    @SuppressWarnings("unchecked")
    public RequestCommand<?> dequeue() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                notEmpty.await();
            }

            RequestCommand<?> command = (RequestCommand<?>) items[takeIndex];
            items[takeIndex] = null; // Liberar referencia para el GC
            if (++takeIndex == capacity) {
                takeIndex = 0;
            }
            count--;
            totalDequeuedCount++;

            notFull.signal();
            return command;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Desencola con tiempo de espera maximo.
     */
    @SuppressWarnings("unchecked")
    public RequestCommand<?> dequeue(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (nanos <= 0L) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }

            RequestCommand<?> command = (RequestCommand<?>) items[takeIndex];
            items[takeIndex] = null;
            if (++takeIndex == capacity) {
                takeIndex = 0;
            }
            count--;
            totalDequeuedCount++;

            notFull.signal();
            return command;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    public boolean isFull() {
        lock.lock();
        try {
            return count == capacity;
        } finally {
            lock.unlock();
        }
    }

    public long getTotalEnqueuedCount() {
        lock.lock();
        try {
            return totalEnqueuedCount;
        } finally {
            lock.unlock();
        }
    }

    public long getTotalDequeuedCount() {
        lock.lock();
        try {
            return totalDequeuedCount;
        } finally {
            lock.unlock();
        }
    }
}
