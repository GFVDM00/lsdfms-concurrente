package pe.edu.unmsm.lsdfms.server;

import pe.edu.unmsm.lsdfms.index.ParallelIndexEngine;
import pe.edu.unmsm.lsdfms.pack.PackEngineAdapter;
import pe.edu.unmsm.lsdfms.storage.RafStorageEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prueba end-to-end de los endpoints HTTP, PACK e INDEX. */
public final class ServerIntegrationTest {

    private ServerIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("server-integration-test-");
        int port = freePort();
        Path data = root.resolve("policies_data.dat");
        Path metadata = root.resolve("metadata.properties");

        try (RafStorageEngine storage = new RafStorageEngine(data.toFile(), metadata.toFile())) {
            NativeHttpServer server = new NativeHttpServer(port, storage, 100);
            server.setIndexEngine(new ParallelIndexEngine(root, 1));
            server.setPackEngine(new PackEngineAdapter());
            server.start();
            try {
                testCrudAndMaintenance(port);
            } finally {
                server.stop(0);
            }
        } finally {
            deleteTree(root);
        }
        System.out.println("All ServerIntegration tests passed.");
    }

    private static void testCrudAndMaintenance(int port) throws Exception {
        String policy = """
                {"id":1001,"dni":"45892147","titular":"Ana Torres",
                 "tipoSeguro":"Salud Integral","departamento":"Lima",
                 "prima":1450.50,"fechaEmision":"2026-03-15"}
                """;
        Response insert = request(port, "POST", "/api/policies", policy);
        check(insert.status == 201, "POST status: " + insert);
        long offset = extractOffset(insert.body);
        check(offset >= 0, "offset de insercion");

        Response get = request(port, "GET", "/api/policies?offset=" + offset, null);
        check(get.status == 200 && get.body.contains("\"id\":1001"), "GET: " + get);

        String updated = """
                {"id":1001,"dni":"45892147","titular":"Ana Torres Actualizada",
                 "tipoSeguro":"Salud Integral Plus","departamento":"Lima",
                 "prima":1600.00,"fechaEmision":"2026-03-15"}
                """;
        Response update = request(port, "PUT", "/api/policies?offset=" + offset, updated);
        check(update.status == 200, "PUT status: " + update);
        long newOffset = extractOffset(update.body);
        check(newOffset >= 0 && newOffset != offset, "nuevo offset de actualizacion");

        Response statusBefore = request(port, "GET", "/api/status", null);
        check(statusBefore.body.contains("\"isIndexSynced\":false"), "indice desactualizado");

        Response maintenance = request(port, "POST", "/api/maintenance", "");
        check(maintenance.status == 200, "maintenance: " + maintenance);

        Response search = request(port, "GET", "/api/search?column=id&key=1001", null);
        check(search.status == 200 && search.body.contains("Ana Torres Actualizada"),
                "busqueda id: " + search);

        Response statusAfter = request(port, "GET", "/api/status", null);
        check(statusAfter.body.contains("\"isIndexSynced\":true"), "indice sincronizado");
        check(statusAfter.body.contains("\"totalRecords\":1"), "PACK dejo un registro activo");
        check(statusAfter.body.contains("\"deletedRecords\":0"), "PACK limpio eliminados");

        long packedOffset = extractOffset(search.body);
        Response delete = request(port, "DELETE", "/api/policies?offset=" + packedOffset, null);
        check(delete.status == 200, "DELETE: " + delete);
    }

    private static Response request(int port, String method, String path, String body)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        if (body != null && !body.isEmpty()) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return new Response(status, response);
    }

    private static long extractOffset(String json) {
        Matcher matcher = Pattern.compile("\"offset\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Response(int status, String body) {
        @Override
        public String toString() {
            return "status=" + status + ", body=" + body;
        }
    }
}
