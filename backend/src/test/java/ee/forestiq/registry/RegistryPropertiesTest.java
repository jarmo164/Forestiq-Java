package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegistryPropertiesTest {

    @Test
    void appliesExplicitRegistryAuditAndRikConfigurationWithoutTreatingBlankCredentialsAsConfigured() {
        RegistryProperties properties = new RegistryProperties();
        RegistryProperties.Maaamet maaamet = new RegistryProperties.Maaamet();
        RegistryProperties.Metsaregister metsaregister = new RegistryProperties.Metsaregister();
        RegistryProperties.AutoRefresh autoRefresh = new RegistryProperties.AutoRefresh();
        RegistryProperties.Audit audit = new RegistryProperties.Audit();
        RegistryProperties.Rik rik = new RegistryProperties.Rik();

        maaamet.setConnectTimeoutSeconds(20);
        metsaregister.setPageSize(500);
        metsaregister.setMaxPagesPerQuery(8);
        metsaregister.setSortField("feature_id");
        metsaregister.setBulkPagesPerRun(4);
        metsaregister.setMinRequestIntervalMs(250);
        autoRefresh.setForestMaxAgeHours(12);
        audit.setRetentionDays(14);
        audit.setTroubleshootingPayloadRetentionHours(6);
        audit.setMaxStoredPayloadBytes(4096);
        audit.setMaxDiagnosticSampleBytes(1024);
        audit.setTroubleshootingFullPayloadEnabled(true);
        audit.setCleanupFixedDelayMs(3_600_000L);
        rik.setSoapWsdlUrl("https://rik.example.test/service?WSDL");
        rik.setUsername("  ");
        rik.setPassword("secret");

        properties.setMaaamet(maaamet);
        properties.setMetsaregister(metsaregister);
        properties.setAutoRefresh(autoRefresh);
        properties.setAudit(audit);
        properties.setRik(rik);

        assertThat(properties.getMaaamet().getConnectTimeoutSeconds()).isEqualTo(20);
        assertThat(properties.getMetsaregister().getPageSize()).isEqualTo(500);
        assertThat(properties.getMetsaregister().getMaxPagesPerQuery()).isEqualTo(8);
        assertThat(properties.getMetsaregister().getSortField()).isEqualTo("feature_id");
        assertThat(properties.getMetsaregister().getBulkPagesPerRun()).isEqualTo(4);
        assertThat(properties.getMetsaregister().getMinRequestIntervalMs()).isEqualTo(250);
        assertThat(properties.getAutoRefresh().getForestMaxAgeHours()).isEqualTo(12);
        assertThat(properties.getAudit().getRetentionDays()).isEqualTo(14);
        assertThat(properties.getAudit().isTroubleshootingFullPayloadEnabled()).isTrue();
        assertThat(properties.getRik().isConfigured()).isFalse();

        properties.getRik().setUsername("rik-user");
        assertThat(properties.getRik().isConfigured()).isTrue();
    }
}
