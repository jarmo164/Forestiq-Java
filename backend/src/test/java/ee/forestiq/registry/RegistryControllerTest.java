package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ee.forestiq.registry.ExternalRegistryService.RegistryHealth;

@ExtendWith(MockitoExtension.class)
class RegistryControllerTest {

    @Mock private RegistrySyncService syncService;
    @Mock private RegistryFreshnessService freshnessService;
    @InjectMocks private RegistryController controller;

    @Test
    void routesFreshnessAndRecoveryRequestsWithSafeDefaultAndRequestedBatchSize() {
        controller.freshness();
        controller.recoverStale(null);
        controller.recoverStale(new RegistryController.RecoveryRequest(40));

        verify(freshnessService).dashboard();
        verify(freshnessService).recoverStale(25, "MANUAL");
        verify(freshnessService).recoverStale(40, "MANUAL");
    }

    @Test
    void routesRegistryHealthAndEachParcelRefreshProjection() {
        RegistryHealth health = new RegistryHealth(true, true, true, false);
        when(syncService.registryHealth()).thenReturn(health);
        when(syncService.refreshAll("78401:101:0101")).thenReturn(Map.of());

        assertThat(controller.health()).isSameAs(health);
        assertThat(controller.refreshAll("78401:101:0101")).isEmpty();
        controller.refreshMaaamet("78401:101:0101");
        controller.refreshForestNotifications("78401:101:0101");
        controller.refreshForestPlan("78401:101:0101");

        verify(syncService).registryHealth();
        verify(syncService).refreshAll("78401:101:0101");
        verify(syncService).refreshMaaamet("78401:101:0101");
        verify(syncService).refreshForestNotifications("78401:101:0101");
        verify(syncService).refreshForestPlan("78401:101:0101");
    }

    @Test
    void returnsTheRikCountUsingSubmittedIdentityFields() {
        when(syncService.fetchRikCadastreCount("48901010011", "Mari", "Mets")).thenReturn(7);

        Map<String, Integer> response = controller.rikCadastreCount(
            new RegistryController.RikCountRequest("48901010011", "Mari", "Mets"));

        assertThat(response).containsEntry("count", 7);
        verify(syncService).fetchRikCadastreCount("48901010011", "Mari", "Mets");
    }
}
