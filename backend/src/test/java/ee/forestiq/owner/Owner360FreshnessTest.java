package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import ee.forestiq.registry.RegistryFreshnessService.SourceFreshness;

class Owner360FreshnessTest {

    private static final Instant SUCCESS = Instant.parse("2026-09-09T10:00:00Z");

    @Test
    void latestSuccessfulFreshSourceIsFresh() {
        assertThat(Owner360Service.registryFreshnessState(source(10, 10, 0, SUCCESS, null))).isEqualTo("FRESH");
    }

    @Test
    void sourceWithStaleParcelsIsStale() {
        assertThat(Owner360Service.registryFreshnessState(source(10, 7, 3, SUCCESS, null))).isEqualTo("STALE");
    }

    @Test
    void failedRefreshAfterLastSuccessKeepsLastKnownGoodAsFailedRefresh() {
        Instant failure = SUCCESS.plusSeconds(60);
        assertThat(Owner360Service.registryFreshnessState(source(10, 10, 0, SUCCESS, failure))).isEqualTo("FAILED_REFRESH");
    }

    @Test
    void missingSuccessfulGenerationIsUnknownInsteadOfZero() {
        assertThat(Owner360Service.registryFreshnessState(source(10, 0, 10, null, SUCCESS))).isEqualTo("UNKNOWN");
    }

    private static SourceFreshness source(int total, int fresh, int stale, Instant success, Instant failure) {
        return new SourceFreshness(
            "METSAREGISTER_TEATISED",
            "Metsaregistri teatised",
            168,
            total,
            fresh,
            stale,
            success,
            failure,
            failure == null ? 0 : 1,
            "REGISTRY_SYNC_LOG",
            null,
            null);
    }
}