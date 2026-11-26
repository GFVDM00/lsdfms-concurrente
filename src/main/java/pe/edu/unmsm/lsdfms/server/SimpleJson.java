package pe.edu.unmsm.lsdfms.server;

import pe.edu.unmsm.lsdfms.model.InsurancePolicy;
import pe.edu.unmsm.lsdfms.storage.Metadata;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilidad ligera para serializar y deserializar JSON usando unicamente Java estandar.
 * Cumple con RNF-01 (prohibido librerias externas como Gson o Jackson).
 */
public final class SimpleJson {

    private SimpleJson() {}

    /**
     * Parsea un objeto JSON simple plano (clave-valor) a un Map<String, String>.
     */
    public static Map<String, String> parseFlatJson(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.trim().isEmpty()) {
            return map;
        }

        // Expresion regular para extraer pares "clave": "valor" o "clave": numero/boolean
        Pattern pattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|[^,\\}\\s]+)");
        Matcher matcher = pattern.matcher(json);

        while (matcher.find()) {
            String key = matcher.group(1);
            String rawVal = matcher.group(2).trim();
            if (rawVal.startsWith("\"") && rawVal.endsWith("\"")) {
                rawVal = rawVal.substring(1, rawVal.length() - 1);
            }
            map.put(key, rawVal);
        }
        return map;
    }

    /**
     * Reconstruye una InsurancePolicy a partir de un JSON plano.
     */
    public static InsurancePolicy parsePolicy(String json) {
        Map<String, String> map = parseFlatJson(json);
        long id = map.containsKey("id") ? Long.parseLong(map.get("id")) : System.currentTimeMillis();
        String dni = map.getOrDefault("dni", "");
        String titular = map.getOrDefault("titular", "");
        String tipoSeguro = map.getOrDefault("tipoSeguro", "");
        String departamento = map.getOrDefault("departamento", "");
        double prima = map.containsKey("prima") ? Double.parseDouble(map.get("prima")) : 0.0;
        String fechaEmision = map.getOrDefault("fechaEmision", "2026-01-01");

        return new InsurancePolicy(id, dni, titular, tipoSeguro, departamento, prima, fechaEmision);
    }

    public static String policyToJson(InsurancePolicy p, long offset) {
        if (p == null) return "null";
        return "{" +
                "\"id\":" + p.getId() + "," +
                "\"dni\":\"" + escape(p.getDni()) + "\"," +
                "\"titular\":\"" + escape(p.getTitular()) + "\"," +
                "\"tipoSeguro\":\"" + escape(p.getTipoSeguro()) + "\"," +
                "\"departamento\":\"" + escape(p.getDepartamento()) + "\"," +
                "\"prima\":" + p.getPrima() + "," +
                "\"fechaEmision\":\"" + escape(p.getFechaEmision()) + "\"," +
                "\"deleted\":" + p.isDeleted() + "," +
                "\"offset\":" + offset +
                "}";
    }

    public static String metadataToJson(Metadata m, int currentQueueSize) {
        if (m == null) return "{}";
        return "{" +
                "\"activeDataFile\":\"" + escape(m.getActiveDataFile()) + "\"," +
                "\"totalRecords\":" + m.getTotalRecords() + "," +
                "\"activeRecords\":" + m.getActiveRecords() + "," +
                "\"deletedRecords\":" + m.getDeletedRecords() + "," +
                "\"lastOffset\":" + m.getLastOffset() + "," +
                "\"isIndexSynced\":" + m.isIndexSynced() + "," +
                "\"lastCompactedAt\":" + m.getLastCompactedAt() + "," +
                "\"lastIndexedAt\":" + m.getLastIndexedAt() + "," +
                "\"currentQueueSize\":" + currentQueueSize +
                "}";
    }

    public static String responseMessage(boolean success, String message, long offset) {
        return "{" +
                "\"success\":" + success + "," +
                "\"message\":\"" + escape(message) + "\"," +
                "\"offset\":" + offset +
                "}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
