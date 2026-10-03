package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ee.forestiq.config.DistributedJobLock;
import ee.forestiq.integration.IntegrationJobHandler;
import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobResult;

/**
 * Exposes the provider-global Metsaregister WFS mirror through the shared
 * integration job/Admin surface without duplicating source rows per tenant.
 */
@Configuration
class MetsaregisterWfsMirrorJobConfiguration {
    private static final String GLOBAL_LOCK = "forestiq:metsaregister:wfs-mirror";
    private static final int DEFAULT_LAYERS_PER_RUN = 3;

    @Bean
    IntegrationJobHandler metsaregisterWfsMirrorJob(
        DSLContext dsl,
        RegistryProperties properties,
        MetsaregisterLayerManifestService manifest,
        MetsaregisterWfsLayerCatalogService catalog,
        MetsaregisterWfsSchemaService schemas,
        MetsaregisterWfsMirrorService mirror,
        DistributedJobLock jobLock
    ) {
        return new IntegrationJobHandler() {
            @Override
            public IntegrationJobKey key() {
                return IntegrationJobKey.METSAREGISTER_WFS_MIRROR;
            }

            @Override
            public String displayName() {
                return "Metsaregistri WFS lähtepeegel";
            }

            @Override
            public String provider() {
                return "Metsaregister WFS";
            }

            @Override
            public String purpose() {
                return "Kanoniseeritud Metsaregistri provider-global PostGIS peegel manifestis LOCAL_DOMAIN_DATA režiimiga kinnitatud domeeniandmetele";
            }

            @Override
            public String scheduleDescription() {
                long hours = Math.max(1L, properties.getAutoRefresh().getFixedDelayMs() / 3_600_000L);
                return "Kontroll iga " + hours + " tunni järel; tegelik refresh-intervall on kihipõhine";
            }

            @Override
            public boolean configured() {
                String url = properties.getMetsaregister().getWfsUrl();
                return url != null && !url.isBlank();
            }

            @Override
            public boolean enabled() {
                return properties.getAutoRefresh().isEnabled();
            }

            @Override
            public List<UUID> scheduledOrganizations() {
                UUID coordinator = dsl.select(field(name("id"), UUID.class))
                    .from(table("organization"))
                    .orderBy(field(name("id"), UUID.class).asc())
                    .limit(1)
                    .fetchOne(field(name("id"), UUID.class));
                return coordinator == null ? List.of() : List.of(coordinator);
            }

            @Override
            public IntegrationJobResult run(UUID organizationId, Map<String, String> parameters) {
                var locked = jobLock.tryRun(GLOBAL_LOCK, () -> executeMirror(
                    dsl, manifest, catalog, schemas, mirror, parameters == null ? Map.of() : parameters));
                if (!locked.acquired()) {
                    return IntegrationJobResult.partial(
                        Map.of("backlogRemaining", true, "scope", "PROVIDER_GLOBAL"),
                        "Metsaregistri globaalne WFS peegeldus töötab juba teises protsessis.");
                }
                return locked.value();
            }
        };
    }

    private static IntegrationJobResult executeMirror(
        DSLContext dsl,
        MetsaregisterLayerManifestService manifest,
        MetsaregisterWfsLayerCatalogService catalog,
        MetsaregisterWfsSchemaService schemas,
        MetsaregisterWfsMirrorService mirror,
        Map<String, String> parameters
    ) {
        String requestedLayer = trimToNull(parameters.get("layer"));
        if (requestedLayer != null && !manifest.isLocalDomainLayer(requestedLayer)) {
            throw new IllegalArgumentException(
                "Metsaregister layer is not approved for LOCAL_DOMAIN_DATA ingestion: " + requestedLayer);
        }
        String mode = trimToNull(parameters.get("mode"));
        boolean fullRefresh = "full".equalsIgnoreCase(mode)
            || Boolean.parseBoolean(parameters.getOrDefault("force", "false"));
        int maxLayers = requestedLayer != null
            ? 1
            : parseMaxLayers(parameters.get("maxLayers"), fullRefresh ? 50 : DEFAULT_LAYERS_PER_RUN);

        var catalogResult = catalog.reconcileCapabilities();
        var schemaResult = schemas.inspectApprovedLayers();

        List<String> selectedLayers;
        if (requestedLayer != null) {
            selectedLayers = List.of(requestedLayer);
        } else {
            selectedLayers = selectLayers(dsl, maxLayers, fullRefresh);
        }
        List<LayerOutcome> outcomes = runSelectedLayers(mirror, selectedLayers);

        long succeeded = outcomes.stream().filter(LayerOutcome::succeeded).count();
        long failures = outcomes.size() - succeeded;
        long completed = outcomes.stream().filter(outcome -> outcome.succeeded() && outcome.complete()).count();
        long pages = outcomes.stream().mapToLong(LayerOutcome::pages).sum();
        long rows = outcomes.stream().mapToLong(LayerOutcome::rows).sum();
        long retries = outcomes.stream().mapToLong(LayerOutcome::retries).sum();
        long durationMs = outcomes.stream().mapToLong(LayerOutcome::durationMs).sum();
        long remaining = remainingLayers(dsl);
        long due = dueLayerCount(dsl);
        long stagingLayers = stagingLayerCount(dsl);
        long checkpointLagRows = checkpointLagRows(dsl);
        boolean selectedBacklog = selectedBacklog(failures, completed, outcomes.size());
        boolean globalBacklog = globalBacklog(remaining, due, stagingLayers);
        boolean backlogRemaining = backlogRemaining(requestedLayer != null, selectedBacklog, globalBacklog);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("scope", "PROVIDER_GLOBAL");
        metrics.put("manifestVersion", manifest.version());
        metrics.put("mode", requestedLayer != null ? "SINGLE_LAYER" : fullRefresh ? "FULL" : "SCHEDULED_DUE");
        if (requestedLayer != null) metrics.put("requestedLayer", requestedLayer);
        metrics.put("providerLayers", catalogResult.providerLayers());
        metrics.put("newlyDiscovered", catalogResult.newlyDiscovered());
        metrics.put("missingApproved", catalogResult.missingApproved());
        metrics.put("approvedPresent", catalogResult.approvedPresent());
        metrics.put("schemasInspected", schemaResult.inspected());
        metrics.put("schemasReady", schemaResult.ready());
        metrics.put("schemasUnsupported", schemaResult.unsupported());
        metrics.put("schemaDrift", schemaResult.schemaDrift());
        metrics.put("schemaFailures", schemaResult.failed());
        metrics.put("layersAttempted", outcomes.size());
        metrics.put("layersSucceeded", succeeded);
        metrics.put("layerFailures", failures);
        metrics.put("layersCompleted", completed);
        metrics.put("pages", pages);
        metrics.put("rows", rows);
        metrics.put("retries", retries);
        metrics.put("durationMs", durationMs);
        metrics.put("stagingLayers", stagingLayers);
        metrics.put("checkpointLagRows", checkpointLagRows);
        metrics.put("remainingLayers", remaining);
        metrics.put("dueLayers", due);
        metrics.put("perLayer", outcomes.stream().map(MetsaregisterWfsMirrorJobConfiguration::layerMetrics).toList());
        metrics.put("globalBacklogRemaining", globalBacklog);
        metrics.put("backlogRemaining", backlogRemaining);

        boolean metadataProblem = catalogResult.missingApproved() > 0
            || schemaResult.unsupported() > 0
            || schemaResult.schemaDrift() > 0
            || schemaResult.failed() > 0;
        if (metadataProblem || backlogRemaining) {
            String message = failures > 0
                ? "Metsaregistri WFS peegel edeneb; " + failures
                    + " kihi refresh ebaõnnestus, teised kihid jätkasid iseseisvalt."
                : "Metsaregistri WFS peegel edeneb; osa kihte vajab veel ingestimist, refreshi või skeemi kontrolli.";
            return IntegrationJobResult.partial(Map.copyOf(metrics), message);
        }
        return IntegrationJobResult.success(Map.copyOf(metrics),
            "Kõik manifestis kinnitatud Metsaregistri domeenikihid on provider-global PostGIS peeglis ajakohased.");
    }

    static List<LayerOutcome> runSelectedLayers(
        MetsaregisterWfsMirrorService mirror,
        List<String> selectedLayers
    ) {
        List<LayerOutcome> outcomes = new ArrayList<>(selectedLayers.size());
        for (String layerName : selectedLayers) {
            long started = System.nanoTime();
            try {
                MetsaregisterWfsMirrorService.LayerRun run = mirror.syncLayer(layerName);
                outcomes.add(new LayerOutcome(
                    layerName,
                    true,
                    run.complete(),
                    run.pagesThisRun(),
                    run.rowsThisRun(),
                    run.retriesThisRun(),
                    elapsedMs(started),
                    null));
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) throw exception;
                outcomes.add(new LayerOutcome(
                    layerName, false, false, 0, 0, 0, elapsedMs(started), safeError(exception)));
            }
        }
        return List.copyOf(outcomes);
    }

    private static Map<String, Object> layerMetrics(LayerOutcome outcome) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("layer", outcome.layerName());
        values.put("outcome", outcome.succeeded() ? "SUCCEEDED" : "FAILED");
        values.put("complete", outcome.complete());
        values.put("pages", outcome.pages());
        values.put("rows", outcome.rows());
        values.put("retries", outcome.retries());
        values.put("durationMs", outcome.durationMs());
        if (outcome.error() != null) values.put("error", outcome.error());
        return Map.copyOf(values);
    }

    private static List<String> selectLayers(DSLContext dsl, int maxLayers, boolean force) {
        String dueClause = force ? "" : """
            AND (
                l.state='FAILED'
                OR EXISTS (
                    SELECT 1 FROM metsaregister_wfs_generation g
                     WHERE g.layer_name=l.layer_name AND g.state='STAGING'
                )
                OR l.last_success_at IS NULL
                OR l.last_success_at + (l.refresh_interval_minutes * INTERVAL '1 minute') <= CURRENT_TIMESTAMP
            )
            """;
        return dsl.fetch("""
            SELECT l.layer_name
              FROM metsaregister_wfs_layer l
             WHERE l.approved=TRUE
               AND l.enabled=TRUE
               AND l.consumption_mode='LOCAL_DOMAIN_DATA'
               AND l.provider_present=TRUE
               AND l.sort_property IS NOT NULL
               AND l.state NOT IN ('PENDING_SCHEMA','UNSUPPORTED','SCHEMA_DRIFT')
            """ + dueClause + """
             ORDER BY
               CASE WHEN EXISTS (
                   SELECT 1 FROM metsaregister_wfs_generation g
                    WHERE g.layer_name=l.layer_name AND g.state='STAGING'
               ) THEN 0 ELSE 1 END,
               l.refresh_priority,
               l.last_success_at ASC NULLS FIRST,
               l.layer_name
             LIMIT ?
            """, Math.max(1, Math.min(maxLayers, 50)))
            .getValues("layer_name", String.class);
    }

    private static long remainingLayers(DSLContext dsl) {
        return dsl.selectCount()
            .from(table("metsaregister_wfs_layer"))
            .where(field(name("approved"), Boolean.class).isTrue()
                .and(field(name("enabled"), Boolean.class).isTrue())
                .and(field(name("consumption_mode"), String.class).eq("LOCAL_DOMAIN_DATA"))
                .and(field(name("provider_present"), Boolean.class).isTrue())
                .and(field(name("current_generation_id"), UUID.class).isNull()
                    .or(field(name("state"), String.class).ne("HEALTHY"))))
            .fetchOne(0, long.class);
    }

    private static long dueLayerCount(DSLContext dsl) {
        Long count = dsl.fetchOne("""
            SELECT count(*)::bigint AS c
              FROM metsaregister_wfs_layer l
             WHERE l.approved=TRUE
               AND l.enabled=TRUE
               AND l.consumption_mode='LOCAL_DOMAIN_DATA'
               AND l.provider_present=TRUE
               AND l.sort_property IS NOT NULL
               AND l.state NOT IN ('PENDING_SCHEMA','UNSUPPORTED','SCHEMA_DRIFT')
               AND (
                   l.state='FAILED'
                   OR EXISTS (
                       SELECT 1 FROM metsaregister_wfs_generation g
                        WHERE g.layer_name=l.layer_name AND g.state='STAGING'
                   )
                   OR l.last_success_at IS NULL
                   OR l.last_success_at + (l.refresh_interval_minutes * INTERVAL '1 minute') <= CURRENT_TIMESTAMP
               )
            """).get("c", Long.class);
        return count == null ? 0L : count;
    }

    private static long stagingLayerCount(DSLContext dsl) {
        return dsl.selectCount()
            .from(table("metsaregister_wfs_generation"))
            .where(field(name("state"), String.class).eq("STAGING"))
            .fetchOne(0, long.class);
    }

    private static long checkpointLagRows(DSLContext dsl) {
        Long lag = dsl.fetchOne("""
            SELECT COALESCE(SUM(
                GREATEST(COALESCE(g.provider_number_matched, g.rows_staged) - g.rows_staged, 0)
            ), 0)::bigint AS lag
              FROM metsaregister_wfs_generation g
              JOIN metsaregister_wfs_layer l ON l.layer_name=g.layer_name
             WHERE g.state='STAGING'
               AND l.approved=TRUE
               AND l.enabled=TRUE
               AND l.consumption_mode='LOCAL_DOMAIN_DATA'
               AND l.provider_present=TRUE
            """).get("lag", Long.class);
        return lag == null ? 0L : lag;
    }

    private static int parseMaxLayers(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Math.max(1, Math.min(Integer.parseInt(value), 50));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static boolean selectedBacklog(long failures, long completed, int selectedLayers) {
        return failures > 0 || completed < selectedLayers;
    }

    static boolean globalBacklog(long remainingLayers, long dueLayers, long stagingLayers) {
        return remainingLayers > 0 || dueLayers > 0 || stagingLayers > 0;
    }

    static boolean backlogRemaining(boolean singleLayerRun, boolean selectedBacklog, boolean globalBacklog) {
        return singleLayerRun ? selectedBacklog : selectedBacklog || globalBacklog;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
        message = message.replaceAll("[\\r\\n]+", " ");
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record LayerOutcome(
        String layerName,
        boolean succeeded,
        boolean complete,
        long pages,
        long rows,
        long retries,
        long durationMs,
        String error
    ) { }
}
