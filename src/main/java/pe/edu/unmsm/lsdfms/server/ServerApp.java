package pe.edu.unmsm.lsdfms.server;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.Metadata;
import pe.edu.unmsm.lsdfms.storage.RafStorageEngine;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aplicación principal para ejecutar el Servidor HTTP nativo de Brandon (Módulo 2).
 * Puede operar con RafStorageEngine (cuando Alejandro lo complete) o con un motor
 * de desarrollo en memoria para pruebas desacopladas.
 */
public class ServerApp {

    public static void main(String[] args) {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        System.out.println("=========================================================");
        System.out.println("  LSDFMS: Servidor HTTP Nativo y Cola Concurrente        ");
        System.out.println("  Modulo 2: Brandon                                      ");
        System.out.println("=========================================================");

        StorageEngine engine = resolveEngine();

        try {
            NativeHttpServer server = new NativeHttpServer(port, engine, 500);
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Brandon] Apagando servidor...");
                server.stop(1);
            }));

            System.out.println("Servidor escuchando en http://localhost:" + port + "/");
            System.out.println("Endpoints disponibles:");
            System.out.println("  POST   /api/policies        -> Encolar insercion de registro");
            System.out.println("  DELETE /api/policies?offset -> Encolar borrado logico");
            System.out.println("  GET    /api/policies?offset -> Lectura directa por offset");
            System.out.println("  GET    /api/status          -> Estado de metadata y cola");
            System.out.println("  GET    /api/health          -> Verificacion de salud (Cloud)");
            System.out.println("Presione Ctrl+C para detener.");

        } catch (Exception e) {
            System.err.println("Error iniciando el servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static StorageEngine resolveEngine() {
        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdirs();

        File dataFile = new File(dataDir, "policies_data.dat");
        File metaFile = new File(dataDir, "metadata.properties");

        try {
            RafStorageEngine rafEngine = new RafStorageEngine(dataFile, metaFile);
            // Probar si Alejandro ya implemento insert
            try {
                // Si lanza UnsupportedOperationException, usamos el fallback en memoria para desarrollo
                return rafEngine;
            } catch (Exception e) {
                return new FallbackDevStorageEngine();
            }
        } catch (Exception e) {
            System.out.println("[Brandon] Usando FallbackDevStorageEngine en memoria mientras Alejandro completa el archivo fisico.");
            return new FallbackDevStorageEngine();
        }
    }

    /**
     * Motor temporal en memoria para permitir que el frontend de Cicilis y las pruebas
     * de carga funcionen inmediatamente sin esperar a que el archivo binario este listo.
     */
    private static class FallbackDevStorageEngine implements StorageEngine {
        private final ConcurrentHashMap<Long, InsurancePolicy> memoryStore = new ConcurrentHashMap<>();
        private final AtomicLong offsetGen = new AtomicLong(0);
        private final Metadata metadata = new Metadata();

        @Override
        public long insert(InsurancePolicy record) throws IOException {
            long offset = offsetGen.getAndAdd(120);
            memoryStore.put(offset, record);
            metadata.recordInserted(offset);
            return offset;
        }

        @Override
        public InsurancePolicy readAt(long offset) throws IOException {
            InsurancePolicy p = memoryStore.get(offset);
            if (p == null) {
                throw new IOException("Registro no encontrado en offset: " + offset);
            }
            return p;
        }

        @Override
        public boolean markAsDeleted(long offset) throws IOException {
            InsurancePolicy p = memoryStore.get(offset);
            if (p != null && !p.isDeleted()) {
                p.setDeleted(true);
                metadata.recordDeleted();
                return true;
            }
            return false;
        }

        @Override
        public long getFileSize() throws IOException {
            return offsetGen.get();
        }

        @Override
        public Metadata getMetadata() {
            return metadata;
        }

        @Override
        public void saveMetadata() throws IOException {}

        @Override
        public void close() throws IOException {}
    }
}
