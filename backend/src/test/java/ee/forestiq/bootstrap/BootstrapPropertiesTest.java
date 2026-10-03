package ee.forestiq.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BootstrapPropertiesTest {

    @Test
    void legacyPublicDatasetBootstrapIsDisabledByDefault() {
        BootstrapProperties properties = new BootstrapProperties();

        assertThat(properties.isLegacyPublicDatasetsEnabled()).isFalse();
    }

    @Test
    void legacyPublicDatasetBootstrapRequiresExplicitMigrationFlag() {
        BootstrapProperties properties = new BootstrapProperties();

        properties.setLegacyPublicDatasetsEnabled(true);

        assertThat(properties.isLegacyPublicDatasetsEnabled()).isTrue();
    }
}
