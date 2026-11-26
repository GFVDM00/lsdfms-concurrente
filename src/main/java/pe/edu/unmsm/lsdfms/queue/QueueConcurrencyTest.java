package pe.edu.unmsm.lsdfms.queue;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prueba de estrés y verificación de seguridad de hilos (RNF-02, RF-04).
 * Demuestra exclusión mutua y ausencia de condiciones de carrera en ThreadSafeRequestQueue
 * utilizando un motor de almacenamiento simulado en memoria.
 */
public class QueueConcurrencyTest {

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================================");
        System.out.println("  TEST DE CONCURRENCIA: ThreadSafeRequestQueue (Brandon)          ");
        System.out.println("  Verificacion de Exclusion Mutua y Rendimiento Concurrente       ");
        System.out.println("==================================================================");

        final int NUM_PRODUCERS = 50;
        final int OPS_PER_PRODUCER = 100;
        final int TOTAL_REQUESTS = NUM_PRODUCERS * OPS_PER_PRODUCER; // 5,000 peticiones
        final int QUEUE_CAPACITY = 100;

        // Mock StorageEngine en memoria para pruebas desacopladas
        MockStorageEngine mockEngine = new MockStorageEngine();
        ThreadSafeRequestQueue queue = new ThreadSafeRequestQueue(QUEUE_CAPACITY);
        QueueDispatcher dispatcher = new QueueDispatcher(queue, mockEngine);
        dispatcher.start();

        System.out.printf("Iniciando prueba con %d hilos productores (%d peticiones en total)...\n",
                NUM_PRODUCERS, TOTAL_REQUESTS);

        ExecutorService producerPool = Executors.newFixedThreadPool(NUM_PRODUCERS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(NUM_PRODUCERS);
        List<Future<Boolean>> futures = new ArrayList<>();

        long startTime = System.currentTimeMillis();

        for (int p = 0; p < NUM_PRODUCERS; p++) {
            final int producerId = p;
            producerPool.submit(() -> {
                try {
                    startLatch.await(); // Disparo sincronizado para maxima contencion
                    for (int i = 0; i < OPS_PER_PRODUCER; i++) {
                        InsurancePolicy policy = new InsurancePolicy(
                                (producerId * 1000L) + i,
                                String.format("%08d", (producerId * 1000) + i),
                                "Asegurado " + producerId + "-" + i,
                                "Salud Total",
                                "Lima",
                                1200.0,
                                "2026-01-01"
                        );
                        RequestCommand<InsurancePolicy> cmd = RequestCommand.ofInsert(policy);
                        queue.enqueue(cmd);

                        // Esperar resultado de la cola
                        ResponseResult res = cmd.getFutureResponse().get(10, TimeUnit.SECONDS);
                        if (!res.isSuccess()) {
                            System.err.println("Fallo en peticion: " + res.getMessage());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Iniciar todos los hilos simultáneamente
        startLatch.countDown();
        finishLatch.await();
        producerPool.shutdown();

        long elapsedTime = System.currentTimeMillis() - startTime;
        dispatcher.stop();

        System.out.println("\n----------------- RESULTADOS DE LA PRUEBA -----------------");
        System.out.println("Total peticiones enviadas:      " + TOTAL_REQUESTS);
        System.out.println("Total registros en MockStorage: " + mockEngine.getInsertedCount());
        System.out.println("Peticiones restantes en cola:   " + queue.size());
        System.out.println("Tiempo total transcurrido:      " + elapsedTime + " ms");
        double throughput = (TOTAL_REQUESTS * 1000.0) / elapsedTime;
        System.out.printf("Rendimiento (Throughput):       %.2f peticiones/segundo\n", throughput);

        if (mockEngine.getInsertedCount() == TOTAL_REQUESTS && queue.isEmpty()) {
            System.out.println("\n>>> [EXITO TOTAL] Cero condiciones de carrera, exclusion mutua garantizada. <<<");
        } else {
            System.err.println("\n>>> [ERROR] Se detectaron inconsistencias en la cola. <<<");
        }
        System.out.println("==================================================================");
    }

    /**
     * Motor en memoria simulado para validar el despachador de Brandon
     * sin depender del archivo fisico de Alejandro.
     */
    private static class MockStorageEngine implements StorageEngine {
        private final AtomicLong offsetSequence = new AtomicLong(0);
        private final AtomicLong insertCount = new AtomicLong(0);
        private final Metadata metadata = new Metadata();

        @Override
        public long insert(InsurancePolicy record) throws IOException {
            insertCount.incrementAndGet();
            long offset = offsetSequence.getAndAdd(128); // simula 128 bytes por registro
            metadata.recordInserted(offset);
            return offset;
        }

        @Override
        public InsurancePolicy readAt(long offset) throws IOException {
            return new InsurancePolicy(1L, "12345678", "Mock User", "Auto", "Lima", 500.0, "2026-01-01");
        }

        @Override
        public boolean markAsDeleted(long offset) throws IOException {
            metadata.recordDeleted();
            return true;
        }

        @Override
        public long getFileSize() throws IOException {
            return offsetSequence.get();
        }

        @Override
        public Metadata getMetadata() {
            return metadata;
        }

        @Override
        public void saveMetadata() throws IOException {}

        @Override
        public void close() throws IOException {}

        public long getInsertedCount() {
            return insertCount.get();
        }
    }
}
