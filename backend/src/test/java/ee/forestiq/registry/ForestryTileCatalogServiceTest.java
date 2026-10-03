package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ForestryTileCatalogServiceTest {
    private final MetsaregisterLayerManifestService manifest =
        new MetsaregisterLayerManifestService(new ObjectMapper());
    private final ForestryTileCatalogService catalog =
        new ForestryTileCatalogService(new ObjectMapper(), manifest);

    @Test
    void everyTileLayerIsAnApprovedLocalDomainLayerWithExplicitBudgets() {
        assertThat(catalog.layers()).isNotEmpty().allSatisfy(layer -> {
            assertThat(manifest.isLocalDomainLayer(layer.key())).isTrue();
            assertThat(layer.minZoom()).isGreaterThanOrEqualTo(9);
            assertThat(layer.maxZoom()).isGreaterThanOrEqualTo(layer.minZoom());
            assertThat(layer.featureBudget()).isPositive();
            assertThat(layer.byteBudget()).isPositive();
            assertThat(layer.coldP95Ms()).isPositive();
            assertThat(layer.warmP95Ms()).isPositive().isLessThanOrEqualTo(layer.coldP95Ms());
            assertThat(layer.databaseP95Ms()).isPositive().isLessThanOrEqualTo(layer.coldP95Ms());
            assertThat(layer.lowZoomPolicy()).isEqualTo("HIDDEN_BELOW_MIN_ZOOM");
            assertThat(layer.authorizationScope()).isEqualTo("AUTHENTICATED");
            assertThat(layer.properties()).contains("featureKey");
        });
    }

    @Test
    void catalogContainsOnlyMapFacingLocalForestryLayers() {
        assertThat(catalog.layers()).extracting(ForestryTileCatalogService.Layer::key)
            .containsExactlyInAnyOrder(
                "metsaregister:teatis",
                "metsaregister:teatis_arhiiv",
                "metsaregister:eraldis",
                "metsaregister:isearasused",
                "metsaregister:kahjustused")
            .doesNotContain(
                "metsaregister:eraldis_element",
                "metsaregister:kl_puuliik");
    }

    @Test
    void tilePropertiesAreAnExplicitSmallAllowList() {
        Set<String> allowed = Set.of(
            "featureKey", "cadastralCode", "notificationKey", "providerStatus", "archived",
            "compartmentId", "code", "cause", "severity");
        assertThat(catalog.layers()).allSatisfy(layer ->
            assertThat(allowed).containsAll(layer.properties()));
    }
}
