package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobService;

class RegistryWorkersTest {

    @Test
    void autoRefreshDoesNothingUntilAnOperatorExplicitlyEnablesIt() {
        RegistryProperties properties = new RegistryProperties();
        IntegrationJobService jobs = mock(IntegrationJobService.class);
        RegistryAutoRefreshWorker worker = new RegistryAutoRefreshWorker(properties, jobs);

        worker.refreshStalePublicData();

        verifyNoInteractions(jobs);
    }

    @Test
    void autoRefreshSubmitsEveryRegistryProjectionThroughTheSharedRunner() {
        RegistryProperties properties = new RegistryProperties();
        properties.getAutoRefresh().setEnabled(true);
        IntegrationJobService jobs = mock(IntegrationJobService.class);
        RegistryAutoRefreshWorker worker = new RegistryAutoRefreshWorker(properties, jobs);

        worker.refreshStalePublicData();

        verify(jobs).runScheduledAll(IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        verify(jobs).runScheduledAll(IntegrationJobKey.FOREST_NOTIFICATIONS);
        verify(jobs).runScheduledAll(IntegrationJobKey.FOREST_PLAN);
    }

    @Test
    void auditRetentionRefreshesGaugesAndIncrementsCountersOnlyForActualCleanup() {
        RegistrySyncAuditRepository repository = mock(RegistrySyncAuditRepository.class);
        when(repository.cleanupExpired()).thenReturn(new RegistrySyncAuditRepository.CleanupResult(3, 2));
        when(repository.rowCount()).thenReturn(11L);
        when(repository.largestStoredPayloadBytes()).thenReturn(4096L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RegistryAuditRetentionWorker worker = new RegistryAuditRetentionWorker(repository, meters);

        worker.cleanup();

        assertThat(meters.get("forestiq.registry.audit.deleted.rows").counter().count()).isEqualTo(2d);
        assertThat(meters.get("forestiq.registry.audit.trimmed.payloads").counter().count()).isEqualTo(3d);
        assertThat(meters.get("forestiq.registry.audit.rows").gauge().value()).isEqualTo(11d);
        assertThat(meters.get("forestiq.registry.audit.largest.payload.bytes").gauge().value()).isEqualTo(4096d);
        verify(repository).cleanupExpired();
        verify(repository).rowCount();
        verify(repository).largestStoredPayloadBytes();
    }
}
