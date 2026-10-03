package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class MetsaregisterMapGatewayControllerTest {
    private final MetsaregisterLayerManifestService manifest =
        new MetsaregisterLayerManifestService(new ObjectMapper());
    private final ForestryTileCatalogService tileCatalog =
        new ForestryTileCatalogService(new ObjectMapper(), manifest);

    @Test
    void safeRegistryUsesPublishedGenerationForLocalMvtAndForestIqProxyForExternalWms() {
        UUID generationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MetsaregisterForestryGenerationService generations = mock(MetsaregisterForestryGenerationService.class);
        when(generations.currentGenerationId()).thenReturn(generationId);

        var controller = new MetsaregisterMapGatewayController(
            manifest,
            mock(MetsaregisterMapGatewayService.class),
            generations,
            tileCatalog,
            mock(ForestryMapTileRepository.class));

        var registry = controller.layers();
        assertThat(registry.forestryGenerationId()).isEqualTo(generationId);
        assertThat(registry.manifestVersion()).isEqualTo(manifest.version());
        assertThat(registry.tileCatalogVersion()).isEqualTo(tileCatalog.version());

        var local = registry.layers().stream()
            .filter(layer -> layer.key().equals("metsaregister:teatis"))
            .findFirst().orElseThrow();
        assertThat(local.transport()).isEqualTo("MVT");
        assertThat(local.businessMode()).isEqualTo("LOCAL_DOMAIN_DATA");
        assertThat(local.generationId()).isEqualTo(generationId);
        assertThat(local.authorizationScope()).isEqualTo("AUTHENTICATED");
        assertThat(local.cachePolicy()).isEqualTo("GENERATION_IMMUTABLE_PRIVATE_1D");
        assertThat(local.tileUrlTemplate()).contains(generationId.toString())
            .startsWith("/api/v1/map/metsaregister/mvt/");
        assertThat(local.tileUrlTemplate()).doesNotContain("http://", "https://", "token", "password", "secret");

        var external = registry.layers().stream()
            .filter(layer -> layer.key().equals("metsaregister:katsealad"))
            .findFirst().orElseThrow();
        assertThat(external.transport()).isEqualTo("WMS");
        assertThat(external.businessMode()).isEqualTo("EXTERNAL_MAP");
        assertThat(external.generationId()).isNull();
        assertThat(external.tileUrlTemplate()).startsWith("/api/v1/map/metsaregister/wms/")
            .doesNotContain("http://", "https://", "token", "password", "secret");
    }

    @Test
    void localMvtIsExplicitlyUnavailableUntilCanonicalGenerationIsPublished() {
        MetsaregisterForestryGenerationService generations = mock(MetsaregisterForestryGenerationService.class);
        when(generations.currentGenerationId()).thenReturn(null);
        var controller = new MetsaregisterMapGatewayController(
            manifest,
            mock(MetsaregisterMapGatewayService.class),
            generations,
            tileCatalog,
            mock(ForestryMapTileRepository.class));

        var local = controller.layers().layers().stream()
            .filter(layer -> layer.key().equals("metsaregister:teatis"))
            .findFirst().orElseThrow();
        assertThat(local.available()).isFalse();
        assertThat(local.tileUrlTemplate()).isNull();
        assertThat(local.generationId()).isNull();
    }
}
