package ee.forestiq.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetsisPortfolioPropertiesTest {

    @Test
    void defaultPageSafetyLimitSupportsLargeProductionSnapshots() {
        MetsisPortfolioProperties properties = new MetsisPortfolioProperties();

        assertThat(properties.getMaxPagesPerRun()).isEqualTo(5_000);
    }
}
