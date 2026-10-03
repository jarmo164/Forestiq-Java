package ee.forestiq.integration;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class IntegrationRunParameterCodec {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    IntegrationRunParameterCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, String> parameters(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return Map.of();
        try {
            Map<String, String> parsed = objectMapper.readValue(json, STRING_MAP);
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    boolean metricTrue(String metrics, String... names) {
        if (metrics == null || metrics.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(metrics);
            for (String name : names) {
                if (root.path(name).asBoolean(false)) return true;
            }
        } catch (Exception ignored) {
            // A malformed metrics payload is not allowed to manufacture a healthy signal.
        }
        return false;
    }
}
