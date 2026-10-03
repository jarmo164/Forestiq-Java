package ee.forestiq.registry;

import static ee.forestiq.jooq.Tables.DATA_CHANGE_SIGNAL;
import static ee.forestiq.jooq.Tables.LAND_PARCEL;
import static ee.forestiq.jooq.Tables.REGISTRY_SYNC_LOG;
import static org.jooq.impl.DSL.max;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.forestiq.config.DistributedJobLock;
import ee.forestiq.security.OrganizationContext;

/**
 * Turns the immutable registry_sync_log into an operator-facing freshness SLA.
 * The source log remains the audit trail; this service only derives current risk
 * and runs explicitly bounded recovery batches.
 */
@Service
public class RegistryFreshnessService {

    private static final List<SourceDefinition> SOURCES = List.of(
        new SourceDefinition("MAAAMET_WFS", "Maa- ja Ruumiameti katastrigeneratsioon", true),
        new SourceDefinition("METSAREGISTER_TEATISED", "Metsaregistri teatised", false),
        new SourceDefinition("METSAREGISTER_ERALDISED", "Metsaregistri eraldised", false)
    );

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;
    private final RegistryProperties properties;
    private final RegistrySyncService syncService;
    private final RegistryDataRepository dataRepository;
    private final RegistryBulkRecoveryService bulkRecovery;
    private final DistributedJobLock jobLock;

    @Autowired
    public RegistryFreshnessService(DSLContext dsl, OrganizationContext organizationContext, RegistryProperties properties,
                                    RegistrySyncService syncService, RegistryDataRepository dataRepository,
                                    RegistryBulkRecoveryService bulkRecovery, DistributedJobLock jobLock) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
        this.properties = properties;
        this.syncService = syncService;
        this.dataRepository = dataRepository;
        this.bulkRecovery = bulkRecovery;
        this.jobLock = jobLock;
    }

    // Kept package-visible so lightweight dashboard unit tests do not need the network recovery collaborators.
    RegistryFreshnessService(DSLContext dsl, OrganizationContext organizationContext, RegistryProperties properties,
                             RegistrySyncService syncService) {
        this(dsl, organizationContext, properties, syncService, null, null, null);
    }

    @Transactional(readOnly = true)
    public RegistryFreshnessDashboard dashboard() {
        UUID orgId = organizationContext.requiredOrganizationId();
        return dashboardForOrganization(orgId);
    }

    @Transactional(readOnly = true)
    public RegistryFreshnessDashboard dashboardForOrganization(UUID orgId) {
        int parcelCount = dsl.fetchCount(LAND_PARCEL, LAND_PARCEL.ORGANIZATION_ID.eq(orgId));
        CadastreGenerationFreshness cadastreGeneration = currentCadastreGeneration();
        List<SourceFreshness> sources = new ArrayList<>();
        for (SourceDefinition source : SOURCES) {
            int maxAgeHours = source.cadastre() ? properties.getAutoRefresh().getCadastreMaxAgeHours() : properties.getAutoRefresh().getForestMaxAgeHours();
            Instant cutoff = Instant.now().minus(Math.max(1, maxAgeHours), ChronoUnit.HOURS);
            int fresh = source.cadastre() && cadastreGeneration.available() ? parcelCount : countFresh(orgId, source.registry(), cutoff);
            Instant latestSuccess = source.cadastre() && cadastreGeneration.available()
                ? cadastreGeneration.publishedAt()
                : latest(orgId, source.registry(), "SUCCESS");
            Instant latestFailure = latest(orgId, source.registry(), "FAILED");
            int failures24h = dsl.fetchCount(REGISTRY_SYNC_LOG, REGISTRY_SYNC_LOG.ORGANIZATION_ID.eq(orgId)
                .and(REGISTRY_SYNC_LOG.REGISTRY_NAME.eq(source.registry()))
                .and(REGISTRY_SYNC_LOG.STATUS.eq("FAILED"))
                .and(REGISTRY_SYNC_LOG.CREATED_AT.greaterOrEqual(dbTime(Instant.now().minus(24, ChronoUnit.HOURS)))));
            String evidence = source.cadastre() && cadastreGeneration.available()
                ? "SOURCE_GENERATION"
                : source.cadastre() ? "LEGACY_MAAAMET_WFS_LOG" : "REGISTRY_SYNC_LOG";
            sources.add(new SourceFreshness(source.registry(), source.label(), maxAgeHours, parcelCount, fresh,
                Math.max(0, parcelCount - fresh), latestSuccess, latestFailure, failures24h, evidence,
                source.cadastre() ? cadastreGeneration.generationId() : null,
                source.cadastre() ? cadastreGeneration.sourceDate() : null));
        }
        List<StaleParcel> staleParcels = staleParcels(orgId, 50);
        int openSignals = dsl.fetchCount(DATA_CHANGE_SIGNAL, DATA_CHANGE_SIGNAL.ORGANIZATION_ID.eq(orgId)
            .and(DATA_CHANGE_SIGNAL.DISMISSED_AT.isNull()));
        return new RegistryFreshnessDashboard(Instant.now(), properties.getAutoRefresh().isEnabled(),
            properties.getAutoRefresh().getFixedDelayMs(), properties.getAutoRefresh().getBatchSize(), sources, staleParcels, openSignals);
    }

    public RecoveryRun recoverStale(int requestedBatchSize, String trigger) {
        UUID orgId = organizationContext.requiredOrganizationId();
        return withRecoveryLock(() -> recoverOrganization(orgId, requestedBatchSize, trigger), trigger);
    }

    public RecoveryRun recoverAllOrganizations(String trigger) {
        return withRecoveryLock(() -> {
            int considered = 0;
            int successful = 0;
            int failed = 0;
            boolean checkpointed = false;
            List<UUID> organizations = dsl.select(LAND_PARCEL.ORGANIZATION_ID).from(LAND_PARCEL)
                .groupBy(LAND_PARCEL.ORGANIZATION_ID).fetch(LAND_PARCEL.ORGANIZATION_ID);
            for (UUID orgId : organizations) {
                RecoveryRun run = recoverOrganization(orgId, properties.getAutoRefresh().getBatchSize(), trigger);
                considered += run.considered();
                successful += run.successful();
                failed += run.failed();
                checkpointed |= run.message().contains("checkpoint");
            }
            String message = checkpointed
                ? "Osa Metsaregistri batchidest checkpointiti; järgmine jooks jätkab samast startIndex-ist."
                : failed == 0 ? "Aegunud andmete taastamispartii lõpetati."
                    : "Osa andmeallikate päringutest ebaõnnestus; need jäävad järgmisse taastamispartiisse.";
            return new RecoveryRun(trigger, true, considered, successful, failed, message);
        }, trigger);
    }

    private RecoveryRun recoverOrganization(UUID orgId, int requestedBatchSize, String trigger) {
        int capped = Math.max(1, Math.min(requestedBatchSize, 250));
        RegistrySyncService.BatchSyncResult cadastre = syncService.refreshStaleSourceForOrganization(
            orgId, "MAAAMET_WFS", capped, properties.getAutoRefresh().getCadastreMaxAgeHours());
        Instant forestCutoff = Instant.now().minus(
            Math.max(1, properties.getAutoRefresh().getForestMaxAgeHours()), ChronoUnit.HOURS);
        int forestBatchSize = Math.max(capped, properties.getMetsaregister().getBulkBatchSize());

        var notifications = bulkRecovery.recover(orgId, "METSAREGISTER_TEATISED",
            dataRepository.staleParcelsForSource(orgId, "METSAREGISTER_TEATISED", forestCutoff, forestBatchSize));
        var plan = bulkRecovery.recover(orgId, "METSAREGISTER_ERALDISED",
            dataRepository.staleParcelsForSource(orgId, "METSAREGISTER_ERALDISED", forestCutoff, forestBatchSize));

        int considered = cadastre.considered() + notifications.cadastres() + plan.cadastres();
        int successful = cadastre.successful()
            + (notifications.completed() ? notifications.cadastres() : 0)
            + (plan.completed() ? plan.cadastres() : 0);
        int failed = cadastre.failed();
        boolean checkpointed = (notifications.attempted() && !notifications.completed())
            || (plan.attempted() && !plan.completed());
        String message = checkpointed
            ? "Metsaregistri batch checkpointiti; järgmine jooks jätkab samast startIndex-ist."
            : failed == 0 ? "Aegunud andmete taastamispartii lõpetati."
                : "Osa andmeallikate päringutest ebaõnnestus; need jäävad järgmisse taastamispartiisse.";
        return new RecoveryRun(trigger, true, considered, successful, failed, message);
    }

    private RecoveryRun withRecoveryLock(java.util.function.Supplier<RecoveryRun> work, String trigger) {
        DistributedJobLock.LockedResult<RecoveryRun> locked = jobLock.tryRun("forestiq-registry-refresh", work);
        if (!locked.acquired()) {
            return new RecoveryRun(trigger, false, 0, 0, 0, "Teine registri värskendus töötab juba.");
        }
        return locked.value();
    }

    private int countFresh(UUID orgId, String registry, Instant cutoff) {
        Long count = dsl.fetchOne("""
            SELECT count(*)
            FROM land_parcel p
            WHERE p.organization_id = ?
              AND EXISTS (
                SELECT 1 FROM registry_sync_log l
                WHERE l.organization_id = p.organization_id
                  AND l.cadastral_code = p.cadastral_code
                  AND l.registry_name = ?
                  AND l.status = 'SUCCESS'
                  AND l.created_at >= ?
              )
            """, orgId, registry, Timestamp.from(cutoff)).get(0, Long.class);
        return count == null ? 0 : Math.toIntExact(count);
    }

    private Instant latest(UUID orgId, String registry, String status) {
        OffsetDateTime value = dsl.select(max(REGISTRY_SYNC_LOG.CREATED_AT)).from(REGISTRY_SYNC_LOG)
            .where(REGISTRY_SYNC_LOG.ORGANIZATION_ID.eq(orgId)
                .and(REGISTRY_SYNC_LOG.REGISTRY_NAME.eq(registry))
                .and(REGISTRY_SYNC_LOG.STATUS.eq(status))).fetchOne(0, OffsetDateTime.class);
        return toInstant(value);
    }

    private List<StaleParcel> staleParcels(UUID orgId, int limit) {
        Instant cadastreCutoff = Instant.now().minus(Math.max(1, properties.getAutoRefresh().getCadastreMaxAgeHours()), ChronoUnit.HOURS);
        Instant forestCutoff = Instant.now().minus(Math.max(1, properties.getAutoRefresh().getForestMaxAgeHours()), ChronoUnit.HOURS);
        CadastreGenerationFreshness cadastreGeneration = currentCadastreGeneration();
        List<Record> records = dsl.fetch("""
            SELECT p.cadastral_code, p.name, p.owner_id,
              (SELECT max(l.created_at) FROM registry_sync_log l WHERE l.organization_id=p.organization_id AND l.cadastral_code=p.cadastral_code AND l.registry_name='MAAAMET_WFS' AND l.status='SUCCESS') cadastre_at,
              (SELECT max(l.created_at) FROM registry_sync_log l WHERE l.organization_id=p.organization_id AND l.cadastral_code=p.cadastral_code AND l.registry_name='METSAREGISTER_TEATISED' AND l.status='SUCCESS') notifications_at,
              (SELECT max(l.created_at) FROM registry_sync_log l WHERE l.organization_id=p.organization_id AND l.cadastral_code=p.cadastral_code AND l.registry_name='METSAREGISTER_ERALDISED' AND l.status='SUCCESS') plan_at
            FROM land_parcel p
            WHERE p.organization_id=?
            ORDER BY p.updated_at ASC NULLS FIRST
            LIMIT ?
            """, orgId, Math.max(1, Math.min(limit, 100)));
        List<StaleParcel> result = new ArrayList<>();
        for (Record record : records) {
            Instant cadastreAt = record.get("cadastre_at", Instant.class);
            Instant notificationsAt = record.get("notifications_at", Instant.class);
            Instant planAt = record.get("plan_at", Instant.class);
            List<String> staleSources = new ArrayList<>();
            if (!cadastreGeneration.available() && (cadastreAt == null || cadastreAt.isBefore(cadastreCutoff))) {
                staleSources.add("Maa-amet legacy WFS");
            }
            if (notificationsAt == null || notificationsAt.isBefore(forestCutoff)) staleSources.add("Metsateatised");
            if (planAt == null || planAt.isBefore(forestCutoff)) staleSources.add("Metsaeraldised");
            if (!staleSources.isEmpty()) result.add(new StaleParcel(record.get("cadastral_code", String.class), record.get("name", String.class),
                record.get("owner_id", UUID.class), staleSources));
        }
        return result;
    }

    private CadastreGenerationFreshness currentCadastreGeneration() {
        try {
            Record record = dsl.fetchOne("""
                SELECT c.generation_id, g.source_date, c.published_at
                  FROM geospatial_source_current c
                  JOIN geospatial_generation g ON g.source_key = c.source_key AND g.id = c.generation_id
                 WHERE c.source_key = 'MARU_CADASTRE'
                """);
            if (record == null) return CadastreGenerationFreshness.unavailable();
            UUID generationId = record.get("generation_id", UUID.class);
            java.time.LocalDate sourceDate = record.get("source_date", java.time.LocalDate.class);
            Instant publishedAt = toInstant(record.get("published_at", OffsetDateTime.class));
            return new CadastreGenerationFreshness(
                generationId == null ? null : generationId.toString(),
                sourceDate == null ? null : sourceDate.toString(),
                publishedAt,
                generationId != null);
        } catch (DataAccessException exception) {
            return CadastreGenerationFreshness.unavailable();
        }
    }

    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record SourceDefinition(String registry, String label, boolean cadastre) { }
    public record SourceFreshness(String registry, String label, int maxAgeHours, int totalParcels, int freshParcels,
                                  int staleParcels, Instant lastSuccessfulSyncAt, Instant lastFailedSyncAt,
                                  int failuresLast24Hours, String evidence, String generationId, String sourceDate) { }
    public record StaleParcel(String cadastralCode, String name, UUID ownerId, List<String> staleSources) { }
    public record RegistryFreshnessDashboard(Instant generatedAt, boolean automaticRefreshEnabled, long automaticRefreshDelayMs,
                                             int automaticRefreshBatchSize, List<SourceFreshness> sources, List<StaleParcel> staleParcels,
                                             int openDataSignals) { }
    public record RecoveryRun(String trigger, boolean started, int considered, int successful, int failed, String message) { }
    private record CadastreGenerationFreshness(String generationId, String sourceDate, Instant publishedAt, boolean available) {
        static CadastreGenerationFreshness unavailable() {
            return new CadastreGenerationFreshness(null, null, null, false);
        }
    }
}
