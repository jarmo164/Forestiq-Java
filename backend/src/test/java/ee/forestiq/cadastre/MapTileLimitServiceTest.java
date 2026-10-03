package ee.forestiq.cadastre;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MapTileLimitServiceTest {

    @Test
    void metadataMarksTileTruncatedOnlyAfterFeatureLimitIsExceeded() {
        int limit = GenerationMapTileRepository.maxFeatures(11);

        var complete = MapTileLimitService.limitInfo(11, limit);
        var truncated = MapTileLimitService.limitInfo(11, limit + 1);

        assertThat(complete.truncated()).isFalse();
        assertThat(complete.featureLimit()).isEqualTo(limit);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.observedFeatures()).isEqualTo(limit + 1);
        assertThat(truncated.layer()).isEqualTo("land_parcel");
    }
}
