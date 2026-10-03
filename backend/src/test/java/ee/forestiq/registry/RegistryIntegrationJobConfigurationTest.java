package ee.forestiq.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ee.forestiq.geospatial.cadastre.CadastreGenerationImportService;
import ee.forestiq.geospatial.cadastre.CadastreSnapshotCheckService;
import ee.forestiq.integration.IntegrationJobHandler;
import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobResult;

@ExtendWith(MockitoExtension.class)
class RegistryIntegrationJobConfigurationTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private DSLContext dsl;
    @Mock private RegistryDataRepository dataRepository;
    @Mock private RegistryBulkRecoveryService bulkRecovery;
    @Mock private CadastreSnapshotCheckService snapshotCheckService;
    @Mock private CadastreGenerationImportService generationImportService;

    private RegistryIntegrationJobConfiguration configuration;
    private RegistryProperties properties;

    @BeforeEach
    void setUp() {
        configuration = new RegistryIntegrationJobConfiguration();
        properties = new RegistryProperties();
        properties.getMaaamet().setWfsUrl("https://maaamet.example.test/wfs");
        properties.getMetsaregister().setWfsUrl("https://metsaregister.example.test/wfs");
        properties.getMetsaregister().setBulkBatchSize(50);
        properties.getAutoRefresh().setEnabled(true);
        properties.getAutoRefresh().setFixedDelayMs(7_200_000L);
        properties.getAutoRefresh().setBatchSize(25);
        properties.getAutoRefresh().setMaxBatchesPerRun(1);
        properties.getAutoRefresh().setCadastreMaxAgeHours(48);
        properties.getAutoRefresh().setForestMaxAgeHours(24);
    }

    @Test
    void nationalCadastreJobUsesExactlyOneCoordinatorAndDoesNotFanOutAcrossOrganizations() {
        when(dsl.select(field("id", UUID.class))
            .from(table("organization"))
            .orderBy(field("id", UUID.class).asc())
            .limit(1)
            .fetchOne(field("id", UUID.class)))
            .thenReturn(ORGANIZATION_ID);

        IntegrationJobHandler job = configuration.cadastrePublicDataJob(
            dsl, snapshotCheckService, generationImportService, true);

        assertThat(job.key()).isEqualTo(IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        assertThat(job.displayName()).isEqualTo("Katastri avalikud andmed");
        assertThat(job.provider()).isEqualTo("Maa- ja Ruumiamet");
        assertThat(job.purpose()).contains("GeoPackage").contains("generation");
        assertThat(job.scheduleDescription()).contains("globaalne").contains("ööpäevas");
        assertThat(job.configured()).isTrue();
        assertThat(job.enabled()).isTrue();
        assertThat(job.scheduledOrganizations()).containsExactly(ORGANIZATION_ID);
    }

    @Test
    void nationalCadastreJobHasNoScheduledRunBeforeAnyOrganizationExists() {
        IntegrationJobHandler job = configuration.cadastrePublicDataJob(
            dsl, snapshotCheckService, generationImportService, true);

        assertThat(job.scheduledOrganizations()).isEmpty();
    }

    @Test
    void nationalCadastreJobPublishesVerifiedGenerationAndReportsBalancedMetrics() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        var prepared = new CadastreSnapshotCheckService.SnapshotCheckResult(runId, true, null);
        var counters = new CadastreGenerationImportService.GenerationCounters(12, 3, 751_200, 4);
        var imported = new CadastreGenerationImportService.ImportResult(
            runId, generationId, true, false, "sha256-test", 751_215, counters);

        when(snapshotCheckService.prepareImport("MANUAL")).thenReturn(prepared);
        when(generationImportService.importAndPublish(prepared, "integration-worker")).thenReturn(imported);

        IntegrationJobHandler job = configuration.cadastrePublicDataJob(
            dsl, snapshotCheckService, generationImportService, true);
        IntegrationJobResult result = job.run(ORGANIZATION_ID, Map.of("mode", "full"));

        assertThat(result.outcome()).isEqualTo(IntegrationJobResult.Outcome.SUCCEEDED);
        assertThat(result.metrics())
            .containsEntry("scope", "ALL_ESTONIA")
            .containsEntry("sourceKey", CadastreSnapshotCheckService.SOURCE_KEY)
            .containsEntry("published", true)
            .containsEntry("rows", 751_215L)
            .containsEntry("inserted", 12L)
            .containsEntry("updated", 3L)
            .containsEntry("unchangedRows", 751_200L)
            .containsEntry("removed", 4L)
            .containsEntry("generationId", generationId.toString());
        assertThat(result.message()).contains("valideeriti").contains("avaldati");
        verify(snapshotCheckService).prepareImport("MANUAL");
        verify(generationImportService).importAndPublish(prepared, "integration-worker");
    }

    @Test
    void unchangedNationalSnapshotDoesNotPublishAnotherGeneration() throws Exception {
        UUID runId = UUID.randomUUID();
        var prepared = new CadastreSnapshotCheckService.SnapshotCheckResult(runId, false, null);
        var imported = new CadastreGenerationImportService.ImportResult(
            runId,
            null,
            false,
            true,
            "sha256-same",
            0,
            new CadastreGenerationImportService.GenerationCounters(0, 0, 0, 0));

        when(snapshotCheckService.prepareImport("MANUAL")).thenReturn(prepared);
        when(generationImportService.importAndPublish(prepared, "integration-worker")).thenReturn(imported);

        IntegrationJobHandler job = configuration.cadastrePublicDataJob(
            dsl, snapshotCheckService, generationImportService, true);
        IntegrationJobResult result = job.run(ORGANIZATION_ID, Map.of());

        assertThat(result.outcome()).isEqualTo(IntegrationJobResult.Outcome.SUCCEEDED);
        assertThat(result.metrics()).containsEntry("changed", false).containsEntry("unchanged", true);
        assertThat(result.message()).contains("ei ole muutunud");
    }

    @Test
    void nationalCadastreImportCanBeDisabledWithoutReenablingTenantWfsScheduling() {
        IntegrationJobHandler job = configuration.cadastrePublicDataJob(
            dsl, snapshotCheckService, generationImportService, false);

        assertThat(job.configured()).isTrue();
        assertThat(job.enabled()).isFalse();
    }

    @Test
    void forestJobUsesTheLargerBatchSizeAndDistinguishesCompletedAndCheckpointedRuns() {
        IntegrationJobHandler job = configuration.forestNotificationsJob(dsl, properties, dataRepository, bulkRecovery);
        var refs = List.of(
            new RegistryDataRepository.ParcelRef(ORGANIZATION_ID, "12345:001:0001"),
            new RegistryDataRepository.ParcelRef(ORGANIZATION_ID, "12345:001:0002"));
        when(dataRepository.staleParcelsForSource(eq(ORGANIZATION_ID), eq("METSAREGISTER_TEATISED"), any(), eq(50)))
            .thenReturn(refs, refs);
        when(bulkRecovery.recover(ORGANIZATION_ID, "METSAREGISTER_TEATISED", refs))
            .thenReturn(new RegistryBulkRecoveryService.BulkRun(2, 2, 34, true, true))
            .thenReturn(new RegistryBulkRecoveryService.BulkRun(2, 1, 10, false, true));

        assertThat(job.key()).isEqualTo(IntegrationJobKey.FOREST_NOTIFICATIONS);
        assertThat(job.provider()).isEqualTo("Metsaregister WFS");
        assertThat(job.configured()).isTrue();
        assertThat(job.enabled()).isTrue();

        IntegrationJobResult completed = job.run(ORGANIZATION_ID, Map.of());
        IntegrationJobResult checkpointed = job.run(ORGANIZATION_ID, Map.of());

        assertThat(completed.outcome()).isEqualTo(IntegrationJobResult.Outcome.SUCCEEDED);
        assertThat(completed.metrics()).containsEntry("cadastres", 2).containsEntry("completed", true);
        assertThat(checkpointed.outcome()).isEqualTo(IntegrationJobResult.Outcome.PARTIAL);
        assertThat(checkpointed.message()).contains("checkpointiti");
    }
}
