package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ee.forestiq.integration.IntegrationJobHandler;
import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobResult;

@Configuration
class RegistryIntegrationJobConfiguration {

    @Bean
    IntegrationJobHandler cadastrePublicDataJob(
        DSLContext dsl,
        ee.forestiq.geospatial.cadastre.CadastreSnapshotCheckService snapshotCheckService,
        ee.forestiq.geospatial.cadastre.CadastreGenerationImportService generationImportService,
        @org.springframework.beans.factory.annotation.Value(
            "${forestiq.geospatial.cadastre.import-enabled:${MARU_CADASTRE_IMPORT_ENABLED:true}}")
        boolean importEnabled
    ) {
        return new IntegrationJobHandler() {
            public IntegrationJobKey key() { return IntegrationJobKey.CADASTRE_PUBLIC_DATA; }
            public String displayName() { return "Katastri avalikud andmed"; }
            public String provider() { return "Maa- ja Ruumiamet"; }
            public String purpose() {
                return "Kontrollib üleriigilist katastri GeoPackage snapshot'i ja avaldab uue valideeritud source generation'i.";
            }
            public String scheduleDescription() {
                return "Üks globaalne kontroll ööpäevas; riiklik snapshot uueneb öösiti.";
            }
            public boolean configured() { return true; }
            public boolean enabled() { return importEnabled; }
            public List<UUID> scheduledOrganizations() {
                // Durable integration runs are organization-scoped today. Use exactly one
                // deterministic coordinator row for the national source so the same Estonia-wide
                // artifact is never downloaded once per tenant.
                return coordinatorOrganization(dsl);
            }

            public IntegrationJobResult run(UUID organizationId, Map<String, String> parameters) {
                try {
                    var prepared = snapshotCheckService.prepareImport("MANUAL");
                    var result = generationImportService.importAndPublish(prepared, "integration-worker");

                    Map<String, Object> metrics = new LinkedHashMap<>();
                    metrics.put("scope", "ALL_ESTONIA");
                    metrics.put("sourceKey", ee.forestiq.geospatial.cadastre.CadastreSnapshotCheckService.SOURCE_KEY);
                    metrics.put("changed", prepared.changed());
                    metrics.put("published", result.published());
                    metrics.put("unchanged", result.unchanged());
                    metrics.put("artifactChecksum", result.artifactChecksum());
                    metrics.put("rows", result.rows());
                    metrics.put("inserted", result.counters().inserted());
                    metrics.put("updated", result.counters().updated());
                    metrics.put("unchangedRows", result.counters().unchanged());
                    metrics.put("removed", result.counters().removed());
                    if (result.generationId() != null) {
                        metrics.put("generationId", result.generationId().toString());
                    }

                    String message = result.unchanged()
                        ? "Maa- ja Ruumiameti katastri snapshot ei ole muutunud; uut generation'i ei loodud."
                        : "Uus Maa- ja Ruumiameti katastri generation valideeriti ja avaldati atomaarse cutover'iga.";
                    return IntegrationJobResult.success(Map.copyOf(metrics), message);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Cadastral generation import was interrupted", exception);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Cadastral generation import failed", exception);
                }
            }
        };
    }

    @Bean
    IntegrationJobHandler forestNotificationsJob(DSLContext dsl, RegistryProperties properties,
                                                   RegistryDataRepository dataRepository,
                                                   RegistryBulkRecoveryService bulkRecovery) {
        return bulkForestJob(dsl, properties, dataRepository, bulkRecovery, IntegrationJobKey.FOREST_NOTIFICATIONS,
            "Metsateatised", "Metsateatiste tööprojektsioon", "METSAREGISTER_TEATISED",
            properties.getAutoRefresh().getForestMaxAgeHours());
    }

    @Bean
    IntegrationJobHandler forestPlanJob(DSLContext dsl, RegistryProperties properties,
                                         RegistryDataRepository dataRepository,
                                         RegistryBulkRecoveryService bulkRecovery) {
        return bulkForestJob(dsl, properties, dataRepository, bulkRecovery, IntegrationJobKey.FOREST_PLAN,
            "Metsakorralduskavad ja eraldised", "Metsakorralduskavade ja metsaeraldiste tööprojektsioon",
            "METSAREGISTER_ERALDISED", properties.getAutoRefresh().getForestMaxAgeHours());
    }

    private static IntegrationJobHandler bulkForestJob(DSLContext dsl, RegistryProperties properties,
                                                        RegistryDataRepository dataRepository,
                                                        RegistryBulkRecoveryService bulkRecovery,
                                                        IntegrationJobKey key, String name, String purpose,
                                                        String registry, int maxAgeHours) {
        return new IntegrationJobHandler() {
            public IntegrationJobKey key() { return key; }
            public String displayName() { return name; }
            public String provider() { return "Metsaregister WFS"; }
            public String purpose() { return purpose; }
            public String scheduleDescription() { return schedule(properties); }
            public boolean configured() {
                return properties.getMetsaregister().getWfsUrl() != null
                    && !properties.getMetsaregister().getWfsUrl().isBlank();
            }
            public boolean enabled() { return properties.getAutoRefresh().isEnabled(); }
            public List<UUID> scheduledOrganizations() { return organizations(dsl); }
            public IntegrationJobResult run(UUID organizationId, Map<String, String> parameters) {
                Instant cutoff = Instant.now().minus(Math.max(1, maxAgeHours), ChronoUnit.HOURS);
                int requestedBatch = Math.max(properties.getAutoRefresh().getBatchSize(),
                    properties.getMetsaregister().getBulkBatchSize());
                requestedBatch = Math.max(1, requestedBatch);
                int maxBatches = boundedMaxBatches(properties);

                int batches = 0;
                int cadastres = 0;
                int pages = 0;
                int rows = 0;
                boolean attempted = false;
                boolean drained = false;
                boolean checkpointed = false;

                while (batches < maxBatches && !Thread.currentThread().isInterrupted()) {
                    var stale = dataRepository.staleParcelsForSource(organizationId, registry, cutoff, requestedBatch);
                    if (stale.isEmpty()) {
                        drained = true;
                        break;
                    }
                    var result = bulkRecovery.recover(organizationId, registry, stale);
                    batches++;
                    attempted |= result.attempted();
                    cadastres += result.cadastres();
                    pages += result.pages();
                    rows += result.records();
                    if (!result.completed()) {
                        checkpointed = true;
                        break;
                    }
                    if (stale.size() < requestedBatch) {
                        drained = true;
                        break;
                    }
                }

                boolean backlogRemaining = checkpointed || (!drained && attempted);
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("batches", batches);
                metrics.put("cadastres", cadastres);
                metrics.put("pages", pages);
                metrics.put("rows", rows);
                metrics.put("attempted", attempted);
                metrics.put("completed", !checkpointed);
                metrics.put("backlogRemaining", backlogRemaining);

                if (!attempted) {
                    return IntegrationJobResult.success(Map.copyOf(metrics), "Kõik registriandmed on värsked.");
                }
                if (checkpointed) {
                    return IntegrationJobResult.partial(Map.copyOf(metrics),
                        "Metsaregistri batch checkpointiti; järgmine jooks jätkab samast startIndex-ist.");
                }
                if (backlogRemaining) {
                    return IntegrationJobResult.partial(Map.copyOf(metrics),
                        "Metsaregistri catch-up jõudis ühe jooksu töömahupiirini; backlog jätkub järgmisel tsüklil.");
                }
                return IntegrationJobResult.success(Map.copyOf(metrics),
                    "Metsaregistri stale-järjekord töödeldi atomaarsete batch-snapshotidena lõpuni.");
            }
        };
    }

    private static List<UUID> coordinatorOrganization(DSLContext dsl) {
        UUID coordinator = dsl.select(field("id", UUID.class))
            .from(table("organization"))
            .orderBy(field("id", UUID.class).asc())
            .limit(1)
            .fetchOne(field("id", UUID.class));
        return coordinator == null ? List.of() : List.of(coordinator);
    }

    private static int boundedMaxBatches(RegistryProperties properties) {
        return Math.max(1, Math.min(properties.getAutoRefresh().getMaxBatchesPerRun(), 100));
    }

    private static List<UUID> organizations(DSLContext dsl) {
        return dsl.select(field("id", UUID.class)).from(table("organization")).fetch(field("id", UUID.class));
    }

    private static String schedule(RegistryProperties properties) {
        return "Iga " + Math.max(1, properties.getAutoRefresh().getFixedDelayMs() / 3_600_000L) + " tunni järel";
    }
}
