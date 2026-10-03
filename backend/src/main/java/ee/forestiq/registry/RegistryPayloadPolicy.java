package ee.forestiq.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Defines the storage boundary for upstream registry payloads. Business fields are
 * persisted in normalized columns; audit rows keep a deterministic source hash and
 * only bounded diagnostics unless short-lived troubleshooting payload capture is enabled.
 */
@Component
class RegistryPayloadPolicy {
    private static final int ENVELOPE_OVERHEAD_BYTES = 1_024;

    private final ObjectMapper objectMapper;
    private final RegistryProperties properties;

    RegistryPayloadPolicy(ObjectMapper objectMapper, RegistryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    String auditEnvelope(Object raw, String status) {
        if (raw == null) return null;
        try {
            byte[] canonical = canonicalBytes(raw);
            RegistryProperties.Audit audit = properties.getAudit();
            int maxStored = Math.max(1_024, audit.getMaxStoredPayloadBytes());
            int diagnosticLimit = Math.max(256,
                Math.min(audit.getMaxDiagnosticSampleBytes(), Math.max(256, maxStored - ENVELOPE_OVERHEAD_BYTES)));

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("sha256", sha256(canonical));
            envelope.put("sourceBytes", canonical.length);

            boolean captureFull = audit.isTroubleshootingFullPayloadEnabled()
                && canonical.length <= Math.max(0, maxStored - ENVELOPE_OVERHEAD_BYTES);
            if (captureFull) {
                envelope.put("payload", objectMapper.readTree(canonical));
                envelope.put("diagnosticTruncated", false);
            } else if (!"SUCCESS".equalsIgnoreCase(status)) {
                String sample = redact(new String(canonical, StandardCharsets.UTF_8));
                byte[] sampleBytes = sample.getBytes(StandardCharsets.UTF_8);
                boolean truncated = sampleBytes.length > diagnosticLimit;
                if (truncated) sample = utf8Prefix(sampleBytes, diagnosticLimit);
                envelope.put("diagnosticSample", sample);
                envelope.put("diagnosticTruncated", truncated || canonical.length > diagnosticLimit);
            }
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception ignored) {
            return "{\"auditEncodingError\":true}";
        }
    }

    String compactFeatureAttributes(Object raw) {
        try {
            byte[] canonical = canonicalBytes(raw);
            return objectMapper.writeValueAsString(Map.of(
                "sourceHash", sha256(canonical),
                "sourceBytes", canonical.length
            ));
        } catch (Exception ignored) {
            return "{\"sourceHash\":\"unavailable\",\"sourceBytes\":0}";
        }
    }

    String sourceHash(Object raw) {
        try {
            return sha256(canonicalBytes(raw));
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    /** Reads both the new compact representation and legacy raw JSON rows. */
    String storedFeatureHash(String attributes) {
        if (attributes == null || attributes.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(attributes);
            JsonNode stored = node.get("sourceHash");
            if (stored != null && stored.isTextual()) return stored.asText();
            return sha256(objectMapper.writeValueAsBytes(canonicalize(node)));
        } catch (Exception ignored) {
            return sha256(attributes.getBytes(StandardCharsets.UTF_8));
        }
    }

    private byte[] canonicalBytes(Object value) throws Exception {
        JsonNode tree = objectMapper.valueToTree(value);
        return objectMapper.writeValueAsBytes(canonicalize(tree));
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode item : node) result.add(canonicalize(item));
            return result;
        }
        ObjectNode result = objectMapper.createObjectNode();
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        fields.forEach((key, value) -> result.set(key, canonicalize(value)));
        return result;
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String redact(String value) {
        return value
            .replaceAll("(?i)(authorization|token|password|secret)\\s*[:=]\\s*[^,;\\s\\\"}]+", "$1=[redacted]")
            .replaceAll("[\\r\\n]+", " ")
            .trim();
    }

    private static String utf8Prefix(byte[] value, int maxBytes) {
        int end = Math.min(value.length, maxBytes);
        while (end > 0 && (value[end - 1] & 0xC0) == 0x80) end--;
        return new String(value, 0, Math.max(0, end), StandardCharsets.UTF_8);
    }
}
