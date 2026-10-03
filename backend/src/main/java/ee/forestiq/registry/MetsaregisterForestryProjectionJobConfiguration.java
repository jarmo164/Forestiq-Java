package ee.forestiq.registry;

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

/** Shared integration-job surface for canonical forestry publication. */
@Configuration
class MetsaregisterForestryProjectionJobConfiguration {
    private static final String LOCK_KEY = "forestiq:metsaregister:forestry-canonical";

    @Bean
    IntegrationJobHandler metsaregisterForestryCanonicalJob(
        DSLContext dsl,
        RegistryProperties properties,
        MetsaregisterForestryProjectionService projection,
        DistributedJobLock jobLock
    ) {
        return new IntegrationJobHandler() {
            @Override
            public IntegrationJobKey key() {
                return IntegrationJobKey.METSAREGISTER_FORESTRY_CANONICAL;
            }

            @Override
            public String displayName() {
                return "Metsaregistri canonical metsandus";
            }

            @Override
            public String provider() {
                return "Metsaregister / ForestIQ";
            }

            @Override
            public String purpose() {
                return "Avaldab manifestis kinnitatud Metsaregistri raw-generation'id üheks generation-safe ForestIQ metsanduse domeenivaateks";
            }

            @Override
            public String scheduleDescription() {
                long hours = Math.max(1L, properties.getAutoRefresh().getFixedDelayMs() / 3_600_000L);
                return "Kontroll iga " + hours + " tunni järel pärast raw WFS peegli edenemist";
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
                var record = dsl.fetchOne("SELECT id FROM organization ORDER BY id LIMIT 1");
                UUID coordinator = record == null ? null : record.get("id", UUID.class);
                return coordinator == null ? List.of() : List.of(coordinator);
            }

            @Override
            public IntegrationJobResult run(UUID organizationId, Map<String, String> parameters) {
                var locked = jobLock.tryRun(LOCK_KEY, () -> runCanonical(projection));
                if (!locked.acquired()) {
                    return IntegrationJobResult.partial(
                        Map.of("scope", "PROVIDER_GLOBAL", "backlogRemaining", true),
                        "Metsaregistri canonical publication töötab juba teises protsessis.");
                }
                return locked.value();
            }
        };
    }

    private static IntegrationJobResult runCanonical(MetsaregisterForestryProjectionService projection) {
        var before = projection.status();
        if (!before.ready()) {
            return IntegrationJobResult.partial(
                statusMetrics(before),
                "Canonical metsanduse generation ootab kõigi LOCAL_DOMAIN_DATA raw-kihtide täielikku published generation'it.");
        }
        try {
            var result = projection.refresh("SCHEDULED", "integration-job");
            Map<String, Object> metrics = new LinkedHashMap<>(result.metrics());
            metrics.put("scope", "PROVIDER_GLOBAL");
            metrics.put("rawLayerCount", result.status().rawLayers().size());
            metrics.put("backlogRemaining", !result.status().inSync());
            if (!result.status().inSync()) {
                return IntegrationJobResult.partial(Map.copyOf(metrics),
                    "Canonical metsanduse publication valmis, kuid raw layer pointer'id muutusid projection'i ajal; järgmine kontroll ühtlustab seisu.");
            }
            return IntegrationJobResult.success(Map.copyOf(metrics),
                result.changed()
                    ? "Canonical Metsaregistri metsanduse generation avaldati edukalt."
                    : "Canonical Metsaregistri metsanduse generation on juba raw layer setiga kooskõlas.");
        } catch (RuntimeException exception) {
            Map<String, Object> metrics = new LinkedHashMap<>(statusMetrics(projection.status()));
            metrics.put("canonicalProjectionFailure", safeError(exception));
            metrics.put("backlogRemaining", true);
            return IntegrationJobResult.partial(Map.copyOf(metrics),
                "Raw Metsaregistri peegel jääb kasutatavaks, kuid canonical publication ebaõnnestus; eelmine known-good generation jääb current-iks.");
        }
    }

    private static Map<String, Object> statusMetrics(MetsaregisterForestryProjectionService.ProjectionStatus status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scope", "PROVIDER_GLOBAL");
        values.put("manifestVersion", status.manifestVersion());
        values.put("ready", status.ready());
        values.put("inSync", status.inSync());
        values.put("currentGenerationId", status.currentGenerationId() == null ? "" : status.currentGenerationId().toString());
        values.put("missingOrInvalidLayers", status.missingOrInvalidLayers());
        values.put("driftedLayers", status.driftedLayers());
        values.put("rawLayerCount", status.rawLayers().size());
        values.put("backlogRemaining", !status.inSync());
        return Map.copyOf(values);
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String clean = message.replaceAll("[\\r\\n]+", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 500));
    }
}
