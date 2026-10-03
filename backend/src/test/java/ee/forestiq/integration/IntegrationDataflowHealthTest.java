package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import ee.forestiq.config.DistributedJobLock;
import ee.forestiq.security.OrganizationContext;

class IntegrationDataflowHealthTest {
    private final UUID organizationId = UUID.randomUUID();
    private final IntegrationRunRepository runs = mock(IntegrationRunRepository.class);
    private final OrganizationContext organizationContext = mock(OrganizationContext.class);
    private final IntegrationJobHandler handler = mock(IntegrationJobHandler.class);
    private IntegrationJobService service;

    @BeforeEach
    void setUp() {
        when(organizationContext.requiredOrganizationId()).thenReturn(organizationId);
        when(handler.key()).thenReturn(IntegrationJobKey.FOREST_NOTIFICATIONS);
        when(handler.configured()).thenReturn(true);
        when(handler.enabled()).thenReturn(true);
        when(handler.manualSupported()).thenReturn(true);
        when(handler.displayName()).thenReturn("Metsateatised");
        when(handler.provider()).thenReturn("Metsaregister WFS");
        when(handler.purpose()).thenReturn("Test");
        when(handler.scheduleDescription()).thenReturn("Iga 6 tunni järel");
        service = new IntegrationJobService(List.of(handler), runs, organizationContext,
            mock(DistributedJobLock.class), new SyncTaskExecutor(), new ObjectMapper());
    }

    @Test
    void recentSuccessfulFeedWithoutBacklogIsHealthy() {
        var success = run("SUCCEEDED", Instant.now().minusSeconds(3_600), "{}");
        when(runs.latest(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(success);
        when(runs.latestSuccess(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(success);
        when(runs.failureStreak(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(0);
        when(runs.durableBacklog(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(false);

        var health = service.jobsForCurrentOrganization().getFirst().health();

        assertThat(health.state()).isEqualTo("HEALTHY");
        assertThat(health.freshnessSloSeconds()).isEqualTo(24 * 3_600L);
        assertThat(health.latestSuccessAgeSeconds()).isBetween(3_599L, 3_605L);
        assertThat(health.failureStreak()).isZero();
        assertThat(health.backlogRemaining()).isFalse();
    }

    @Test
    void feedOlderThanItsSloIsStale() {
        var success = run("SUCCEEDED", Instant.now().minusSeconds(25 * 3_600L), "{}");
        when(runs.latest(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(success);
        when(runs.latestSuccess(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(success);

        var health = service.jobsForCurrentOrganization().getFirst().health();

        assertThat(health.state()).isEqualTo("STALE");
        assertThat(health.reason()).contains("SLO");
    }

    @Test
    void freshFeedWithCatchupBacklogIsDegraded() {
        var success = run("SUCCEEDED", Instant.now().minusSeconds(1_800), "{\"backlogRemaining\":true}");
        when(runs.latest(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(success);
        when(runs.latestSuccess(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(success);
        when(runs.failureStreak(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(0);

        var health = service.jobsForCurrentOrganization().getFirst().health();

        assertThat(health.state()).isEqualTo("DEGRADED");
        assertThat(health.backlogRemaining()).isTrue();
        assertThat(health.reason()).contains("backlog");
    }

    @Test
    void configuredButDisabledFeedIsReportedSeparatelyFromApplicationHealth() {
        when(handler.enabled()).thenReturn(false);

        var health = service.jobsForCurrentOrganization().getFirst().health();

        assertThat(health.state()).isEqualTo("DISABLED");
        assertThat(health.reason()).contains("välja lülitatud");
        verify(runs, never()).failureStreak(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS);
        verify(runs, never()).durableBacklog(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS);
        verify(runs, never()).cursorLagSeconds(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS);
    }

    @Test
    void failedHealthProbeDegradesFeedInsteadOfBreakingCatalog() {
        var success = run("SUCCEEDED", Instant.now().minusSeconds(1_800), "{}");
        when(runs.latest(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(success);
        when(runs.latestSuccess(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS)).thenReturn(success);
        when(runs.failureStreak(organizationId, IntegrationJobKey.FOREST_NOTIFICATIONS))
            .thenThrow(new RuntimeException("checkpoint unavailable"));

        var health = service.jobsForCurrentOrganization().getFirst().health();

        assertThat(health.state()).isEqualTo("DEGRADED");
        assertThat(health.reason()).contains("tervisemõõdikute lugemine ebaõnnestus");
    }

    private IntegrationJobService.RunView run(String state, Instant finishedAt, String metrics) {
        return new IntegrationJobService.RunView(UUID.randomUUID(), IntegrationJobKey.FOREST_NOTIFICATIONS.name(),
            "SCHEDULED", state, "system", "{}", metrics, null, null,
            finishedAt.minusSeconds(60), finishedAt.minusSeconds(30), finishedAt);
    }
}
