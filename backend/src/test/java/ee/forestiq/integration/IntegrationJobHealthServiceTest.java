package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class IntegrationJobHealthServiceTest {
    private final UUID organizationId = UUID.randomUUID();
    private final IntegrationRunRepository runs = mock(IntegrationRunRepository.class);
    private final IntegrationJobHealthService service = new IntegrationJobHealthService(
        runs, new IntegrationRunParameterCodec(new ObjectMapper()));

    @Test
    void misconfiguredJobDoesNotProbeOptionalHealthTables() {
        var health = service.health(organizationId, IntegrationJobKey.CADASTRE_PUBLIC_DATA,
            "MISCONFIGURED", null, null, null);

        assertThat(health.state()).isEqualTo("MISCONFIGURED");
        assertThat(health.reason()).contains("puudulik");
    }

    @Test
    void successfulFreshRunWithoutBacklogIsHealthy() {
        Instant now = Instant.now();
        var latest = run(now, "{\"backlogRemaining\":false}");
        when(runs.failureStreak(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(0);
        when(runs.durableBacklog(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(false);
        when(runs.cursorLagSeconds(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(60L);

        var health = service.health(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS,
            "ENABLED", latest, latest, null);

        assertThat(health.state()).isEqualTo("HEALTHY");
    }

    @Test
    void metricsBacklogDegradesOtherwiseFreshRun() {
        Instant now = Instant.now();
        var latest = run(now, "{\"catchupRemaining\":true}");
        when(runs.failureStreak(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(0);
        when(runs.durableBacklog(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(false);
        when(runs.cursorLagSeconds(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(60L);

        var health = service.health(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS,
            "ENABLED", latest, latest, null);

        assertThat(health.state()).isEqualTo("DEGRADED");
        assertThat(health.backlogRemaining()).isTrue();
    }

    private static IntegrationJobService.RunView run(Instant finishedAt, String metrics) {
        return new IntegrationJobService.RunView(UUID.randomUUID(), IntegrationJobKey.FOREST_NOTIFICATIONS.name(),
            "SCHEDULED", "SUCCEEDED", "system", "{}", metrics, null, null,
            finishedAt.minusSeconds(30), finishedAt.minusSeconds(20), finishedAt);
    }
}
