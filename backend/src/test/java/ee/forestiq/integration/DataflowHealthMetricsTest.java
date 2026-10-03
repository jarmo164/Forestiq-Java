package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class DataflowHealthMetricsTest {

    @Test
    void publishesBoundedJobLevelHealthMetricsWithoutTenantLabels() {
        IntegrationRunRepository runs = mock(IntegrationRunRepository.class);
        IntegrationJobHandler handler = mock(IntegrationJobHandler.class);
        when(handler.key()).thenReturn(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS);
        when(handler.configured()).thenReturn(true);
        when(handler.enabled()).thenReturn(true);
        when(runs.worstLatestSuccessAgeSeconds(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS)).thenReturn(13 * 3_600L);
        when(runs.worstCursorLagSeconds(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS)).thenReturn(13 * 3_600L);
        when(runs.worstFailureStreak(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS)).thenReturn(2);
        when(runs.anyBacklog(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS)).thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DataflowHealthMetrics metrics = new DataflowHealthMetrics(runs, List.of(handler), registry);

        metrics.refresh();

        String job = IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS.name();
        assertThat(registry.get("forestiq.dataflow.enabled").tag("job", job).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("forestiq.dataflow.stale").tag("job", job).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("forestiq.dataflow.latest.success.age").tag("job", job).gauge().value()).isEqualTo(13 * 3_600.0);
        assertThat(registry.get("forestiq.dataflow.failure.streak").tag("job", job).gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("forestiq.dataflow.backlog").tag("job", job).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("forestiq.dataflow.cursor.lag").tag("job", job).gauge().value()).isEqualTo(13 * 3_600.0);
        assertThat(registry.get("forestiq.dataflow.stale").tag("job", job).gauge().getId().getTags())
            .allMatch(tag -> !tag.getKey().toLowerCase().contains("organization"));
    }
}
