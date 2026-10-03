package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RegistryPayloadPolicyTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void successfulSyncStoresHashAndSizeButNotRawPayloadByDefault() throws Exception {
        RegistryProperties properties = new RegistryProperties();
        RegistryPayloadPolicy policy = new RegistryPayloadPolicy(json, properties);

        String stored = policy.auditEnvelope(json.createObjectNode().put("secret", "large-source-value"), "SUCCESS");
        JsonNode envelope = json.readTree(stored);

        assertThat(envelope.path("sha256").asText()).hasSize(64);
        assertThat(envelope.path("sourceBytes").asInt()).isPositive();
        assertThat(envelope.has("payload")).isFalse();
        assertThat(envelope.has("diagnosticSample")).isFalse();
    }

    @Test
    void failedDiagnosticIsBoundedAndRedactsCredentialLikeValues() throws Exception {
        RegistryProperties properties = new RegistryProperties();
        properties.getAudit().setMaxDiagnosticSampleBytes(256);
        RegistryPayloadPolicy policy = new RegistryPayloadPolicy(json, properties);
        String raw = "authorization=super-secret-token " + "x".repeat(5_000);

        JsonNode envelope = json.readTree(policy.auditEnvelope(raw, "FAILED"));

        assertThat(envelope.path("diagnosticSample").asText()).contains("authorization=[redacted]");
        assertThat(envelope.path("diagnosticSample").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(256);
        assertThat(envelope.path("diagnosticTruncated").asBoolean()).isTrue();
    }

    @Test
    void compactFeatureVersionDoesNotPersistRawAttributesAndReadsLegacyRows() throws Exception {
        RegistryProperties properties = new RegistryProperties();
        RegistryPayloadPolicy policy = new RegistryPayloadPolicy(json, properties);
        JsonNode raw = json.readTree("{\"b\":2,\"a\":1}");

        String compact = policy.compactFeatureAttributes(raw);
        JsonNode stored = json.readTree(compact);

        assertThat(stored.size()).isEqualTo(2);
        assertThat(stored.has("sourceHash")).isTrue();
        assertThat(stored.has("sourceBytes")).isTrue();
        assertThat(policy.storedFeatureHash(compact)).isEqualTo(policy.sourceHash(raw));
        assertThat(policy.storedFeatureHash("{\"a\":1,\"b\":2}"))
            .isEqualTo(policy.sourceHash(raw));
    }

    @Test
    void explicitTroubleshootingPayloadCaptureStillHonorsMaximumStorageSize() throws Exception {
        RegistryProperties properties = new RegistryProperties();
        properties.getAudit().setTroubleshootingFullPayloadEnabled(true);
        properties.getAudit().setMaxStoredPayloadBytes(2_048);
        properties.getAudit().setMaxDiagnosticSampleBytes(512);
        RegistryPayloadPolicy policy = new RegistryPayloadPolicy(json, properties);

        JsonNode small = json.createObjectNode().put("value", "ok");
        JsonNode large = json.createObjectNode().put("value", "x".repeat(10_000));

        assertThat(json.readTree(policy.auditEnvelope(small, "FAILED")).has("payload")).isTrue();
        JsonNode bounded = json.readTree(policy.auditEnvelope(large, "FAILED"));
        assertThat(bounded.has("payload")).isFalse();
        assertThat(bounded.path("diagnosticTruncated").asBoolean()).isTrue();
    }
}
