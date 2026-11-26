package pe.edu.unmsm.lsdfms.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import pe.edu.unmsm.lsdfms.index.IndexEngine;
import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.pack.PackEngine;
import pe.edu.unmsm.lsdfms.queue.QueueDispatcher;
import pe.edu.unmsm.lsdfms.queue.RequestCommand;
import pe.edu.unmsm.lsdfms.queue.RequestType;
import pe.edu.unmsm.lsdfms.queue.ResponseResult;
import pe.edu.unmsm.lsdfms.queue.ThreadSafeRequestQueue;
import pe.edu.unmsm.lsdfms.storage.StorageEngine;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Servidor HTTP Nativo ligero en Java (sin dependencias externas).
 * Implementado por Brandon (Módulo 2).
 * 
 * Cumple con:
 *  - RF-03: Endpoints REST para CRUD y consultas.
 *  - RF-04: Recibe peticiones y las encola en ThreadSafeRequestQueue.
 *  - RF-07: Endpoint de Mantenimiento coordinado en la nube (PACK + INDEX).
 *  - RNF-01: Implementado exclusivamente con com.sun.net.httpserver.HttpServer.
 *  - RNF-04: Listo para desplegar en instancias de nube (AWS EC2 / Azure VM / GCP).
 */
public class NativeHttpServer {

    private final int port;
    private final StorageEngine storageEngine;
    private final ThreadSafeRequestQueue requestQueue;
    private final QueueDispatcher queueDispatcher;

    private IndexEngine indexEngine;
    private HttpServer server;
    private ExecutorService threadPool;

    public NativeHttpServer(int port, StorageEngine storageEngine, int queueCapacity) {
        this.port = port;
        this.storageEngine = storageEngine;
        this.requestQueue = new ThreadSafeRequestQueue(queueCapacity);
        this.queueDispatcher = new QueueDispatcher(this.requestQueue, this.storageEngine);
    }

    public void setPackEngine(PackEngine packEngine) {
        this.queueDispatcher.setPackEngine(packEngine);
    }

    public void setIndexEngine(IndexEngine indexEngine) {
        this.indexEngine = indexEngine;
        this.queueDispatcher.setIndexEngine(indexEngine);
    }

    public synchronized void start() throws IOException {
        int workers = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        this.threadPool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "HttpWorker-" + System.nanoTime() % 1000);
            t.setDaemon(true);
            return t;
        });

        this.queueDispatcher.start();

        this.server = HttpServer.create(new InetSocketAddress(this.port), 0);
        this.server.setExecutor(this.threadPool);

        // Registro de Handlers REST
        this.server.createContext("/api/policies", new PoliciesHandler());
        this.server.createContext("/api/search", new SearchHandler());
        this.server.createContext("/api/maintenance", new MaintenanceHandler());
        this.server.createContext("/api/status", new StatusHandler());
        this.server.createContext("/api/health", new HealthHandler());
        this.server.createContext("/", new StaticFileHandler());

        this.server.start();
        System.out.println("[Brandon] Servidor HTTP iniciado en http://localhost:" + port);
        System.out.println("[Brandon] Cola sincronizada activa con capacidad: " + requestQueue.capacity());
    }

    public synchronized void stop(int delaySeconds) {
        if (server != null) {
            server.stop(delaySeconds);
        }
        if (queueDispatcher != null) {
            queueDispatcher.stop();
        }
        if (threadPool != null) {
            threadPool.shutdown();
        }
        System.out.println("[Brandon] Servidor HTTP y cola detenidos ordenadamente.");
    }

    public ThreadSafeRequestQueue getRequestQueue() {
        return requestQueue;
    }

    // ==========================================
    // HANDLERS HTTP
    // ==========================================

    private class PoliciesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod().toUpperCase();

            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                switch (method) {
                    case "POST" -> handleInsert(exchange);
                    case "PUT" -> handleUpdate(exchange);
                    case "DELETE" -> handleDelete(exchange);
                    case "GET" -> handleGet(exchange);
                    default -> sendJson(exchange, 405, SimpleJson.responseMessage(false, "Metodo no permitido", -1));
                }
            } catch (Exception e) {
                sendJson(exchange, 500, SimpleJson.responseMessage(false, "Error interno: " + e.getMessage(), -1));
            }
        }

        private void handleInsert(HttpExchange exchange) throws Exception {
            String body = readRequestBody(exchange);
            InsurancePolicy policy = SimpleJson.parsePolicy(body);

            RequestCommand<InsurancePolicy> cmd = RequestCommand.ofInsert(policy);
            requestQueue.enqueue(cmd);

            ResponseResult result = cmd.getFutureResponse().get(5, TimeUnit.SECONDS);

            if (result.isSuccess()) {
                sendJson(exchange, 201, SimpleJson.responseMessage(true, result.getMessage(), result.getAffectedOffset()));
            } else {
                sendJson(exchange, 400, SimpleJson.responseMessage(false, result.getMessage(), -1));
            }
        }

        private void handleUpdate(HttpExchange exchange) throws Exception {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            if (!query.containsKey("offset")) {
                sendJson(exchange, 400, SimpleJson.responseMessage(false,
                        "Parametro 'offset' es requerido", -1));
                return;
            }
            long offset = Long.parseLong(query.get("offset"));
            String body = readRequestBody(exchange);
            InsurancePolicy policy = SimpleJson.parsePolicy(body);
            RequestCommand<InsurancePolicy> cmd = RequestCommand.ofUpdate(offset, policy);
            requestQueue.enqueue(cmd);

            ResponseResult result = cmd.getFutureResponse().get(5, TimeUnit.SECONDS);
            if (result.isSuccess()) {
                sendJson(exchange, 200, SimpleJson.responseMessage(true,
                        result.getMessage(), result.getAffectedOffset()));
            } else {
                sendJson(exchange, 404, SimpleJson.responseMessage(false,
                        result.getMessage(), offset));
            }
        }

        private void handleDelete(HttpExchange exchange) throws Exception {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            if (!query.containsKey("offset")) {
                sendJson(exchange, 400, SimpleJson.responseMessage(false, "Parametro 'offset' es requerido", -1));
                return;
            }

            long offset = Long.parseLong(query.get("offset"));
            RequestCommand<Void> cmd = RequestCommand.ofDelete(offset);
            requestQueue.enqueue(cmd);

            ResponseResult result = cmd.getFutureResponse().get(5, TimeUnit.SECONDS);
            if (result.isSuccess()) {
                sendJson(exchange, 200, SimpleJson.responseMessage(true, result.getMessage(), offset));
            } else {
                sendJson(exchange, 404, SimpleJson.responseMessage(false, result.getMessage(), offset));
            }
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            if (!query.containsKey("offset")) {
                sendJson(exchange, 400, SimpleJson.responseMessage(false, "Parametro 'offset' es requerido", -1));
                return;
            }

            long offset = Long.parseLong(query.get("offset"));
            try {
                InsurancePolicy policy = storageEngine.readAt(offset);
                if (policy == null) {
                    sendJson(exchange, 404, SimpleJson.responseMessage(false, "Registro no encontrado", offset));
                } else {
                    sendJson(exchange, 200, SimpleJson.policyToJson(policy, offset));
                }
            } catch (UnsupportedOperationException e) {
                sendJson(exchange, 501, SimpleJson.responseMessage(false,
                        "El motor de almacenamiento no soporta lectura directa", offset));
            } catch (Exception e) {
                sendJson(exchange, 404, SimpleJson.responseMessage(false, "Error leyendo offset " + offset + ": " + e.getMessage(), offset));
            }
        }
    }

    /**
     * Handler de Busqueda Rapida O(log n) por indice (RF-02)
     */
    private class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String column = query.get("column");
            String key = query.get("key");

            if (column == null || key == null) {
                sendJson(exchange, 400, SimpleJson.responseMessage(false, "Parametros 'column' y 'key' son obligatorios", -1));
                return;
            }

            if (indexEngine == null) {
                sendJson(exchange, 503, SimpleJson.responseMessage(false,
                        "Motor de indices no configurado", -1));
                return;
            }

            try {
                Object searchKey = "id".equalsIgnoreCase(column) ? parseLongKey(key) : key;
                long offset = indexEngine.searchOffset(column, searchKey);
                if (offset < 0) {
                    sendJson(exchange, 404, SimpleJson.responseMessage(false, "No se encontro ningun registro para " + column + "=" + key, -1));
                } else {
                    InsurancePolicy policy = storageEngine.readAt(offset);
                    sendJson(exchange, 200, SimpleJson.policyToJson(policy, offset));
                }
            } catch (NumberFormatException e) {
                sendJson(exchange, 400, SimpleJson.responseMessage(false,
                        "La clave de id debe ser numerica", -1));
            } catch (Exception e) {
                sendJson(exchange, 500, SimpleJson.responseMessage(false, "Error en busqueda: " + e.getMessage(), -1));
            }
        }

        private long parseLongKey(String key) {
            try {
                return Long.parseLong(key);
            } catch (NumberFormatException e) {
                throw new NumberFormatException("id no numerico: " + key);
            }
        }
    }

    /**
     * Handler de Mantenimiento Coordinado PACK e INDEX en la nube (RF-07)
     */
    private class MaintenanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, SimpleJson.responseMessage(false, "Solo se permite POST para mantenimiento", -1));
                return;
            }

            try {
                // 1. Encolar PACK
                RequestCommand<Void> packCmd = new RequestCommand<>(RequestType.MAINTENANCE_PACK, null, -1);
                requestQueue.enqueue(packCmd);
                ResponseResult packRes = packCmd.getFutureResponse().get(300, TimeUnit.SECONDS);
                if (!packRes.isSuccess()) {
                    sendJson(exchange, 500, SimpleJson.responseMessage(false,
                            "PACK fallo: " + packRes.getMessage(), -1));
                    return;
                }

                // 2. Encolar INDEX
                RequestCommand<Void> indexCmd = new RequestCommand<>(RequestType.MAINTENANCE_INDEX, null, -1);
                requestQueue.enqueue(indexCmd);
                ResponseResult indexRes = indexCmd.getFutureResponse().get(300, TimeUnit.SECONDS);

                boolean ok = indexRes.isSuccess();
                String msg = "Mantenimiento completado. " + packRes.getMessage() + " | " + indexRes.getMessage();
                sendJson(exchange, ok ? 200 : 500, SimpleJson.responseMessage(ok, msg, -1));

            } catch (Exception e) {
                sendJson(exchange, 500, SimpleJson.responseMessage(false, "Error en ciclo de mantenimiento: " + e.getMessage(), -1));
            }
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String json = SimpleJson.metadataToJson(storageEngine.getMetadata(), requestQueue.size());
            sendJson(exchange, 200, json);
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String healthJson = "{\"status\":\"UP\",\"timestamp\":" + System.currentTimeMillis() + "}";
            sendJson(exchange, 200, healthJson);
        }
    }

    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/")) {
                path = "/index.html";
            }

            File file = new File("src/main/resources/public" + path);
            if (!file.exists() || file.isDirectory()) {
                String notFound = "<h1>404 Not Found</h1><p>Cliente web en desarrollo por Cicilis.</p>";
                byte[] bytes = notFound.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(404, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            String contentType = "text/plain";
            if (path.endsWith(".html")) contentType = "text/html; charset=UTF-8";
            else if (path.endsWith(".css")) contentType = "text/css; charset=UTF-8";
            else if (path.endsWith(".js")) contentType = "application/javascript; charset=UTF-8";

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ==========================================
    // METODOS AUXILIARES
    // ==========================================

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String jsonResponse) throws IOException {
        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getQuery();
        if (query == null) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            } else if (kv.length == 1) {
                map.put(kv[0], "");
            }
        }
        return map;
    }
}
