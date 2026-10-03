package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class IntegrationRunParameterCodecTest {
    private final IntegrationRunParameterCodec codec = new IntegrationRunParameterCodec(new ObjectMapper());

    @Test
    void parametersReturnsImmutableMapAndFallsBackForMalformedJson() {
        assertThat(codec.parameters("{\"from\":\"2026-08-01\"}"))
            .containsExactly(Map.entry("from", "2026-08-01"));
        assertThat(codec.parameters("not-json")).isEmpty();
    }

    @Test
    void toJsonWritesCompactJson() {
        assertThat(codec.toJson(Map.of("updated", 7))).isEqualTo("{\"updated\":7}");
    }

    @Test
    void metricTrueReadsKnownBooleanFlagsOnly() {
        assertThat(codec.metricTrue("{\"backlogRemaining\":true}", "backlogRemaining")).isTrue();
        assertThat(codec.metricTrue("{\"backlogRemaining\":false}", "backlogRemaining")).isFalse();
        assertThat(codec.metricTrue("not-json", "backlogRemaining")).isFalse();
    }
}
