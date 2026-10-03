package ee.forestiq.cadastre;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MapTileScalePolicyTest {

    @Test
    void denseTileCandidateCapsIncreaseWithZoomButRemainBounded() {
        assertThat(MapTileRepository.maxFeatures(0)).isEqualTo(1_500);
        assertThat(MapTileRepository.maxFeatures(8)).isEqualTo(1_500);
        assertThat(MapTileRepository.maxFeatures(9)).isEqualTo(5_000);
        assertThat(MapTileRepository.maxFeatures(12)).isEqualTo(5_000);
        assertThat(MapTileRepository.maxFeatures(13)).isEqualTo(10_000);
        assertThat(MapTileRepository.maxFeatures(22)).isEqualTo(10_000);
    }

    @Test
    void overviewRepresentationHasExplicitGridFeatureAndZoomBudgets() {
        assertThat(MapTileRepository.overviewGridCells()).isEqualTo(12);
        assertThat(MapTileRepository.overviewMaxFeatures()).isEqualTo(4_096);
        assertThat(MapTileController.OVERVIEW_MAX_ZOOM).isEqualTo(10);
    }

    @Test
    void lowAndMidZoomUsePrecomputedSimplifiedGeometry() {
        assertThat(MapTileRepository.usesPrecomputedLowZoomGeometry(0)).isTrue();
        assertThat(MapTileRepository.usesPrecomputedLowZoomGeometry(8)).isTrue();
        assertThat(MapTileRepository.usesPrecomputedLowZoomGeometry(10)).isTrue();
        assertThat(MapTileRepository.usesPrecomputedLowZoomGeometry(11)).isFalse();
        assertThat(MapTileRepository.usesPrecomputedLowZoomGeometry(22)).isFalse();
    }

    @Test
    void etagChangesWhenTilePayloadChanges() {
        String first = MapTileController.etag(new byte[] {1, 2, 3});
        String second = MapTileController.etag(new byte[] {1, 2, 4});

        assertThat(first).startsWith("\"").endsWith("\"");
        assertThat(second).isNotEqualTo(first);
    }
}
