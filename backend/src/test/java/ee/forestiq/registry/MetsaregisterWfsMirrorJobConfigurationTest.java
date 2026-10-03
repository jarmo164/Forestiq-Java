package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import ee.forestiq.config.DistributedJobLock;
import ee.forestiq.integration.IntegrationJobKey;

class MetsaregisterWfsMirrorJobConfigurationTest {

    @Test
    void adminJobDeclaresJavaMirrorAsCanonicalWriter() {
        MetsaregisterWfsMirrorJobConfiguration configuration = new MetsaregisterWfsMirrorJobConfiguration();
        RegistryProperties properties = new RegistryProperties();
        var handler = configuration.metsaregisterWfsMirrorJob(
            mock(DSLContext.class),
            properties,
            mock(MetsaregisterLayerManifestService.class),
            mock(MetsaregisterWfsLayerCatalogService.class),
            mock(MetsaregisterWfsSchemaService.class),
            mock(MetsaregisterWfsMirrorService.class),
            mock(DistributedJobLock.class));

        assertThat(handler.key()).isEqualTo(IntegrationJobKey.METSAREGISTER_WFS_MIRROR);
        assertThat(handler.purpose()).contains("Kanoniseeritud Metsaregistri provider-global PostGIS peegel");
        assertThat(handler.configured()).isTrue();
        assertThat(handler.enabled()).isFalse();
    }

    @Test
    void failedLayerDoesNotStopFollowingLayers() {
        MetsaregisterWfsMirrorService mirror = mock(MetsaregisterWfsMirrorService.class);
        when(mirror.syncLayer("metsaregister:first")).thenReturn(new MetsaregisterWfsMirrorService.LayerRun(
            "metsaregister:first", UUID.randomUUID(), 2, 1_000, 1_000, true, 1));
        when(mirror.syncLayer("metsaregister:broken"))
            .thenThrow(new RuntimeException("provider returned malformed payload"));
        when(mirror.syncLayer("metsaregister:last")).thenReturn(new MetsaregisterWfsMirrorService.LayerRun(
            "metsaregister:last", UUID.randomUUID(), 1, 200, 200, true, 0));

        var outcomes = MetsaregisterWfsMirrorJobConfiguration.runSelectedLayers(
            mirror,
            List.of("metsaregister:first", "metsaregister:broken", "metsaregister:last"));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).extracting(MetsaregisterWfsMirrorJobConfiguration.LayerOutcome::succeeded)
            .containsExactly(true, false, true);
        assertThat(outcomes.get(0).retries()).isEqualTo(1);
        assertThat(outcomes.get(1).error()).contains("malformed payload");
        assertThat(outcomes.get(2).rows()).isEqualTo(200);
        verify(mirror).syncLayer("metsaregister:last");
    }

    @Test
    void singleLayerRunOutcomeIgnoresGlobalBacklogAfterSelectedLayerCompletes() {
        boolean selectedBacklog = MetsaregisterWfsMirrorJobConfiguration.selectedBacklog(0, 1, 1);
        boolean globalBacklog = MetsaregisterWfsMirrorJobConfiguration.globalBacklog(0, 1, 0);

        assertThat(selectedBacklog).isFalse();
        assertThat(globalBacklog).isTrue();
        assertThat(MetsaregisterWfsMirrorJobConfiguration.backlogRemaining(true, selectedBacklog, globalBacklog))
            .isFalse();
        assertThat(MetsaregisterWfsMirrorJobConfiguration.backlogRemaining(false, selectedBacklog, globalBacklog))
            .isTrue();
    }
}
