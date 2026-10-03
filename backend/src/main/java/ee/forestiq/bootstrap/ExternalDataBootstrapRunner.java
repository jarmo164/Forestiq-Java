package ee.forestiq.bootstrap;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import ee.forestiq.inheritance.InheritanceSourceProperties;
import ee.forestiq.integration.IntegrationJobHandler;
import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobResult;

/** Runs all external first-run imports synchronously and terminates the process when finished. */
@Component
@ConditionalOnProperty(name = "forestiq.bootstrap.enabled", havingValue = "true")
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'bootstrap' || '${forestiq.runtime.role:all}' == 'all'")
public class ExternalDataBootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ExternalDataBootstrapRunner.class);

    private final PublicDatasetBootstrapService publicDatasets;
    private final BootstrapProperties properties;
    private final InheritanceSourceProperties inheritanceProperties;
    private final DSLContext dsl;
    private final Map<IntegrationJobKey, IntegrationJobHandler> handlers;
    private final ConfigurableApplicationContext applicationContext;

    public ExternalDataBootstrapRunner(PublicDatasetBootstrapService publicDatasets,
                                       BootstrapProperties properties,
                                       InheritanceSourceProperties inheritanceProperties,
                                       DSLContext dsl,
                                       List<IntegrationJobHandler> handlers,
                                       ConfigurableApplicationContext applicationContext) {
        this.publicDatasets = publicDatasets;
        this.properties = properties;
        this.inheritanceProperties = inheritanceProperties;
        this.dsl = dsl;
        this.handlers = handlers.stream().collect(Collectors.toMap(
            IntegrationJobHandler::key, handler -> handler,
            (left, right) -> left, () -> new EnumMap<>(IntegrationJobKey.class)));
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            log.info("=== ForestIQ external-data bootstrap started ===");
            List<UUID> organizations = organizations();
            if (organizations.isEmpty()) {
                throw new IllegalStateException("ForestIQ database has no organizations; create the tenant before bootstrap.");
            }
            validatePrerequisites();

            if (properties.isLegacyPublicDatasetsEnabled()) {
                log.warn("Legacy public WFS dataset bootstrap is enabled. This migration-only path duplicates "
                    + "canonical cadastre snapshot and Metsaregister generation loaders.");
                List<PublicDatasetBootstrapService.DatasetResult> publicResults = publicDatasets.synchronizeAll();
                publicResults.forEach(result -> log.info(
                    "Public snapshot complete: source={}, pages={}, rows={}, expected={}",
                    result.sourceKey(), result.pages(), result.rows(),
                    result.expectedRows() < 0 ? "unknown" : result.expectedRows()));
            } else {
                log.info("Skipping legacy public WFS dataset bootstrap. Use cadastre snapshot generation import, "
                    + "Metsaregister WFS generation mirror/import, or EELIS paged file downloads instead.");
            }

            runRequiredPrivateSource(IntegrationJobKey.OWNER_CADASTRE_RELATIONS, organizations,
                "MetsIS / Forestek owner-cadastre snapshot");

            if (properties.isLegacyPublicDatasetsEnabled()) {
                PublicDatasetBootstrapService.ProjectionResult projection = publicDatasets.projectToTenantTables();
                log.info("Public snapshots projected: cadastres={}, notifications={}, compartments={}",
                    projection.cadastresUpdated(), projection.notificationsInserted(), projection.compartmentsInserted());
            }

            runOptionalSource(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS, organizations);
            runInheritanceBackfill(organizations);

            log.info("=== ForestIQ external-data bootstrap completed successfully ===");
        } catch (RuntimeException exception) {
            exitCode = 1;
            log.error("ForestIQ external-data bootstrap failed: {}", safeMessage(exception), exception);
        } finally {
            int finalExitCode = exitCode;
            int springExitCode = SpringApplication.exit(applicationContext, () -> finalExitCode);
            System.exit(springExitCode);
        }
    }

    private void validatePrerequisites() {
        if (!properties.isRequirePrivateSources()) return;
        IntegrationJobHandler forestek = handler(IntegrationJobKey.OWNER_CADASTRE_RELATIONS);
        if (!forestek.configured()) {
            throw new IllegalStateException("MetsIS / Forestek owner-cadastre snapshot is not configured.");
        }
        IntegrationJobHandler inheritance = handler(IntegrationJobKey.INHERITANCE_NOTICES);
        if (!inheritance.configured()) {
            throw new IllegalStateException("Ametlikud Teadaanded inheritance source is not configured.");
        }
    }

    private void runRequiredPrivateSource(IntegrationJobKey key, List<UUID> organizations, String description) {
        IntegrationJobHandler handler = handler(key);
        if (!handler.configured()) {
            if (properties.isRequirePrivateSources()) {
                throw new IllegalStateException(description + " is not configured.");
            }
            log.warn("Skipping unconfigured bootstrap source: {}", description);
            return;
        }
        runHandler(handler, targets(handler, organizations), Map.of());
    }

    private void runOptionalSource(IntegrationJobKey key, List<UUID> organizations) {
        IntegrationJobHandler handler = handler(key);
        if (!handler.configured()) {
            log.info("Optional external source {} is not configured; skipping.", handler.provider());
            return;
        }
        runHandler(handler, targets(handler, organizations), Map.of());
    }

    private void runInheritanceBackfill(List<UUID> organizations) {
        IntegrationJobHandler handler = handler(IntegrationJobKey.INHERITANCE_NOTICES);
        if (!handler.configured()) {
            if (properties.isRequirePrivateSources()) {
                throw new IllegalStateException("Ametlikud Teadaanded inheritance source is not configured.");
            }
            log.warn("Skipping unconfigured Ametlikud Teadaanded inheritance source.");
            return;
        }

        int totalDays = Math.max(1, properties.getInheritanceDays());
        int windowDays = Math.max(1, inheritanceProperties.getMaxBackfillDays());
        ZoneId zone = ZoneId.of(inheritanceProperties.getZoneId());
        LocalDate today = LocalDate.now(zone);
        LocalDate cursor = today.minusDays(totalDays - 1L);
        List<UUID> targets = targets(handler, organizations);

        while (!cursor.isAfter(today)) {
            LocalDate end = cursor.plusDays(windowDays - 1L);
            if (end.isAfter(today)) end = today;
            Map<String, String> parameters = Map.of("from", cursor.toString(), "to", end.toString());
            runHandler(handler, targets, parameters);
            cursor = end.plusDays(1);
        }
    }

    private void runHandler(IntegrationJobHandler handler, List<UUID> organizations,
                            Map<String, String> parameters) {
        if (organizations.isEmpty()) {
            throw new IllegalStateException("No target organization resolved for " + handler.provider());
        }
        for (UUID organizationId : organizations) {
            log.info("Bootstrap source {} for organization {} with parameters {}",
                handler.provider(), organizationId, parameters);
            IntegrationJobResult result = handler.run(organizationId, parameters);
            log.info("Bootstrap source {} result: outcome={}, metrics={}, message={}",
                handler.provider(), result.outcome(), result.metrics(), result.message());
            if (result.outcome() != IntegrationJobResult.Outcome.SUCCEEDED) {
                throw new IllegalStateException(handler.provider() + " bootstrap completed only partially: " + result.message());
            }
        }
    }

    private List<UUID> targets(IntegrationJobHandler handler, List<UUID> fallback) {
        if (properties.getOrganizationId() != null && !properties.getOrganizationId().isBlank()) {
            return List.of(parseOrganization(properties.getOrganizationId()));
        }
        List<UUID> configured = handler.scheduledOrganizations();
        return configured == null || configured.isEmpty() ? fallback : configured;
    }

    private List<UUID> organizations() {
        if (properties.getOrganizationId() != null && !properties.getOrganizationId().isBlank()) {
            UUID id = parseOrganization(properties.getOrganizationId());
            boolean exists = dsl.fetchExists(table("organization"), field("id", UUID.class).eq(id));
            if (!exists) throw new IllegalStateException("Configured bootstrap organization does not exist: " + id);
            return List.of(id);
        }
        return dsl.select(field("id", UUID.class))
            .from(table("organization"))
            .orderBy(field("id").asc())
            .fetch(field("id", UUID.class));
    }

    private IntegrationJobHandler handler(IntegrationJobKey key) {
        IntegrationJobHandler handler = handlers.get(key);
        if (handler == null) throw new IllegalStateException("Missing integration handler: " + key);
        return handler;
    }

    private static UUID parseOrganization(String value) {
        try { return UUID.fromString(value.trim()); }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException("forestiq.bootstrap.organization-id must be a UUID", exception);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        message = message.replaceAll("(?i)(token|password|authorization|secret)\\s*[:=]\\s*[^,; ]+", "$1=[redacted]");
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.substring(0, Math.min(1_000, message.length()));
    }
}
