package ee.forestiq.portfolio;

import static ee.forestiq.config.PostgresCopySupport.copyIn;
import static org.jooq.impl.DSL.*;

import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Metrics;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

/**
 * Fetches a complete owner/cadastre snapshot into disposable PostgreSQL staging
 * and applies the validated snapshot atomically with set-based SQL.
 *
 * Provider I/O never holds a canonical-table transaction open. Each provider
 * page is staged immediately, so memory is bounded by one upstream page rather
 * than by the complete portfolio size.
 */
@Service
public class MetsisPortfolioSyncService {
    private static final Logger log = LoggerFactory.getLogger(MetsisPortfolioSyncService.class);
    private static final String SYSTEM_SUBJECT = "system-owner-cadastre-sync";
    private static final String SOURCE = "METSIS_PORTFOLIO";
    private static final String PORTFOLIO_COPY_SQL = """
        COPY owner_portfolio_import_stage
            (run_id, organization_id, source_row_number, owner_code, owner_name, owner_type, cadastral_code)
        FROM STDIN WITH (FORMAT csv)
        """;

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;
    private final MetsisPortfolioProperties properties;
    private final MetsisPortfolioClient client;

    public MetsisPortfolioSyncService(DSLContext dsl, OrganizationContext organizationContext,
                                      MetsisPortfolioProperties properties, MetsisPortfolioClient client) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
        this.properties = properties;
        this.client = client;
    }

    public SyncResult syncCurrentOrganization() {
        return syncOrganization(organizationContext.requiredOrganizationId(), "MANUAL");
    }

    public SyncResult syncOrganization(UUID organizationId, String trigger) {
        UUID configuredOrganization = configuredOrganizationId();
        if (!configuredOrganization.equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Omanikuportfelli teenusekonto ei ole seotud selle ForestIQ organisatsiooniga.");
        }
        LocalDate since = dsl.select(field(name("cursor_date"), LocalDate.class))
            .from(table("metsis_portfolio_sync_checkpoint"))
            .where(field(name("organization_id"), UUID.class).eq(organizationId))
            .fetchOne(0, LocalDate.class);
        UUID snapshotId = UUID.randomUUID();
        try {
            SnapshotSummary snapshot = timedFetchAndStage(snapshotId, organizationId);
            int stagedOwners = stagedOwnerCount(snapshotId, organizationId);
            if (stagedOwners == 0 && activeSourceRelationCount(organizationId) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Omanikuportfelli tühi vastus ei kinnitanud turvaliselt olemasolevate seoste sulgemist.");
            }

            LocalDate successDate = LocalDate.now();
            long applyStarted = System.nanoTime();
            ApplyResult applied;
            try {
                applied = dsl.transactionResult(configuration -> {
                    DSLContext tx = configuration.dsl();
                    normalizeSnapshot(tx, snapshotId, organizationId);
                    ApplyResult result = applySnapshot(tx, snapshotId, organizationId);
                    upsertCheckpoint(tx, organizationId, successDate, snapshot.rawRows(), result.added(), result.closed(), null);
                    cleanupSnapshot(tx, snapshotId);
                    return result;
                });
            } finally {
                Metrics.timer("forestiq.integration.apply.duration", "provider", "metsis")
                    .record(System.nanoTime() - applyStarted, TimeUnit.NANOSECONDS);
            }
            analyzeAfterApply();
            return new SyncResult(trigger, since, successDate, snapshot.pages(), snapshot.rawRows(),
                applied.added(), applied.closed(), 0, "Omaniku–katastri täielik snapshot sünkroniseeriti.");
        } catch (RuntimeException exception) {
            Metrics.counter("forestiq.integration.sync.failures", "provider", "metsis").increment();
            cleanupSnapshotBestEffort(snapshotId);
            persistFailureCheckpoint(organizationId, exception);
            throw exception;
        }
    }

    private SnapshotSummary timedFetchAndStage(UUID runId, UUID orgId) {
        long started = System.nanoTime();
        try {
            return fetchAndStageCompleteSnapshot(runId, orgId);
        } finally {
            Metrics.timer("forestiq.integration.fetch.duration", "provider", "metsis")
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private SnapshotSummary fetchAndStageCompleteSnapshot(UUID runId, UUID orgId) {
        int pageSize = Math.max(1, Math.min(properties.getPageSize(), 10_000));
        int maxPages = Math.max(1, properties.getMaxPagesPerRun());
        int rawRows = 0;
        int pages = 0;
        long sourceRowNumber = 0;
        boolean completed = false;

        for (int page = 0; page < maxPages; page++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Omanikuportfelli sünkroonimine katkestati enne järgmise lehe laadimist.");
            }
            MetsisPortfolioClient.PageResult response = client.fetchPage(page, pageSize);
            pages++;
            rawRows += response.rawRowCount();

            List<PortfolioStageRow> stageRows = new ArrayList<>();
            for (MetsisPortfolioClient.PortfolioRow row : response.rows()) {
                if (row.ownerCode() == null || row.ownerCode().isBlank()
                    || row.ownerName() == null || row.ownerName().isBlank()) {
                    continue;
                }
                sourceRowNumber++;
                String ownerCode = row.ownerCode().trim();
                String ownerName = row.ownerName().trim();
                String ownerType = row.ownerType() == null || row.ownerType().isBlank()
                    ? "PERSON" : row.ownerType().trim();
                Set<String> cadastres = new LinkedHashSet<>();
                if (row.activeCadastres() != null) {
                    for (String cadastre : row.activeCadastres()) {
                        if (cadastre != null && !cadastre.isBlank()) cadastres.add(cadastre.trim());
                    }
                }
                if (cadastres.isEmpty()) {
                    stageRows.add(new PortfolioStageRow(sourceRowNumber, ownerCode, ownerName, ownerType, null));
                } else {
                    for (String cadastre : cadastres) {
                        stageRows.add(new PortfolioStageRow(sourceRowNumber, ownerCode, ownerName, ownerType, cadastre));
                    }
                }
            }
            stagePortfolioPage(runId, orgId, stageRows);

            if (!response.hasMore()) {
                completed = true;
                break;
            }
        }
        if (!completed) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Omanikuportfelli lehekülgede piir täitus enne täieliku snapshoti lõppu.");
        }
        return new SnapshotSummary(pages, rawRows);
    }

    private void stagePortfolioPage(UUID runId, UUID orgId, List<PortfolioStageRow> rows) {
        if (rows.isEmpty()) return;
        if (dsl.configuration().dialect().family() != SQLDialect.POSTGRES) {
            // Focused H2 unit tests use this fallback. Production is PostgreSQL
            // and always takes the native COPY path below.
            for (PortfolioStageRow row : rows) {
                dsl.insertInto(table("owner_portfolio_import_stage"))
                    .columns(field("run_id"), field("organization_id"), field("source_row_number"),
                        field("owner_code"), field("owner_name"), field("owner_type"), field("cadastral_code"))
                    .values(runId, orgId, row.sourceRowNumber(), row.ownerCode(), row.ownerName(),
                        row.ownerType(), row.cadastralCode())
                    .execute();
            }
            return;
        }

        StringBuilder csv = new StringBuilder(Math.max(4096, rows.size() * 96));
        for (PortfolioStageRow row : rows) {
            appendCsv(csv, runId.toString());
            appendCsv(csv, orgId.toString());
            appendCsv(csv, Long.toString(row.sourceRowNumber()));
            appendCsv(csv, row.ownerCode());
            appendCsv(csv, row.ownerName());
            appendCsv(csv, row.ownerType());
            appendCsv(csv, row.cadastralCode());
            csv.append('\n');
        }
        copyIn(dsl, PORTFOLIO_COPY_SQL, new StringReader(csv.toString()));
    }

    private static void appendCsv(StringBuilder target, String value) {
        if (!target.isEmpty() && target.charAt(target.length() - 1) != '\n') target.append(',');
        if (value == null) return;
        target.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') target.append("\"\"");
            else target.append(ch);
        }
        target.append('"');
    }

    private int stagedOwnerCount(UUID runId, UUID orgId) {
        return dsl.fetchOne("""
            SELECT count(DISTINCT owner_code)::int AS owner_count
              FROM owner_portfolio_import_stage
             WHERE run_id=? AND organization_id=?
            """, runId, orgId).get("owner_count", Integer.class);
    }

    private void normalizeSnapshot(DSLContext tx, UUID runId, UUID orgId) {
        tx.execute("""
            INSERT INTO owner_snapshot_row
                (run_id, organization_id, owner_code, owner_name, owner_type)
            SELECT ?, ?, first_row.owner_code, first_row.owner_name, first_row.owner_type
              FROM (
                    SELECT DISTINCT ON (owner_code)
                           owner_code, owner_name, owner_type, source_row_number
                      FROM owner_portfolio_import_stage
                     WHERE run_id=? AND organization_id=?
                     ORDER BY owner_code, source_row_number
                   ) first_row
            ON CONFLICT (run_id, organization_id, owner_code) DO NOTHING
            """, runId, orgId, runId, orgId);

        tx.execute("""
            INSERT INTO owner_parcel_snapshot_row
                (run_id, organization_id, owner_code, owner_name, owner_type, cadastral_code)
            SELECT ?, ?, s.owner_code, s.owner_name, s.owner_type, raw.cadastral_code
              FROM owner_snapshot_row s
              JOIN (
                    SELECT DISTINCT owner_code, cadastral_code
                      FROM owner_portfolio_import_stage
                     WHERE run_id=? AND organization_id=? AND cadastral_code IS NOT NULL
                   ) raw ON raw.owner_code=s.owner_code
             WHERE s.run_id=? AND s.organization_id=?
            ON CONFLICT (run_id, organization_id, owner_code, cadastral_code) DO NOTHING
            """, runId, orgId, runId, orgId, runId, orgId);
    }

    private ApplyResult applySnapshot(DSLContext tx, UUID runId, UUID orgId) {
        Instant seenAt = Instant.now();

        // Canonical owners: preserve user-owned display/status fields on update;
        // only source projection fields are refreshed from the provider.
        tx.execute("""
            INSERT INTO owner
                (id, organization_id, display_name, personal_or_registry_code, owner_type,
                 source_display_name, source_owner_type, status, updated_at)
            SELECT gen_random_uuid(), s.organization_id, s.owner_name, s.owner_code,
                   CASE WHEN upper(s.owner_type)='COMPANY' THEN 'FIRMA' ELSE 'ERAISIK' END,
                   s.owner_name,
                   CASE WHEN upper(s.owner_type)='COMPANY' THEN 'FIRMA' ELSE 'ERAISIK' END,
                   'NEW', CAST(? AS timestamptz)
              FROM owner_snapshot_row s
             WHERE s.run_id=? AND s.organization_id=?
            ON CONFLICT (organization_id, personal_or_registry_code) DO UPDATE
                SET source_display_name=EXCLUDED.source_display_name,
                    source_owner_type=EXCLUDED.source_owner_type,
                    updated_at=EXCLUDED.updated_at
            """, seenAt, runId, orgId);

        // Missing parcel placeholders are created set-wise. The cadastral import
        // remains authoritative and may later enrich geometry/name/area.
        tx.execute("""
            INSERT INTO land_parcel
                (id, organization_id, owner_id, cadastral_code, area_hectares, name, updated_at)
            SELECT gen_random_uuid(), ?, NULL, s.cadastral_code, 0, s.cadastral_code, CAST(? AS timestamptz)
              FROM (SELECT DISTINCT cadastral_code
                      FROM owner_parcel_snapshot_row
                     WHERE run_id=? AND organization_id=?) s
            ON CONFLICT (organization_id, cadastral_code) DO NOTHING
            """, orgId, seenAt, runId, orgId);

        // Provider-active observation contradicting a protected manual inactive
        // decision: record one revision-scoped conflict but never overwrite it.
        tx.execute("""
            INSERT INTO ownership_relation_conflict
                (id, organization_id, owner_id, parcel_id, source, observed_active,
                 relation_revision, status, observed_at, created_at)
            SELECT gen_random_uuid(), r.organization_id, r.owner_id, r.parcel_id, ?, TRUE,
                   r.manual_revision, 'OPEN', CAST(? AS timestamptz), CURRENT_TIMESTAMP
              FROM owner_parcel_snapshot_row s
              JOIN owner o ON o.organization_id=s.organization_id
                          AND o.personal_or_registry_code=s.owner_code
              JOIN land_parcel p ON p.organization_id=s.organization_id
                                AND p.cadastral_code=s.cadastral_code
              JOIN owner_parcel_relation r ON r.organization_id=s.organization_id
                                           AND r.owner_id=o.id AND r.parcel_id=p.id
             WHERE s.run_id=? AND s.organization_id=?
               AND r.manual_override=TRUE AND r.active=FALSE
            ON CONFLICT (organization_id, owner_id, parcel_id, source, observed_active, relation_revision)
            DO NOTHING
            """, SOURCE, seenAt, runId, orgId);

        Integer added = tx.fetchOne("""
            SELECT count(*)::int AS added
              FROM owner_parcel_snapshot_row s
              JOIN owner o ON o.organization_id=s.organization_id
                          AND o.personal_or_registry_code=s.owner_code
              JOIN land_parcel p ON p.organization_id=s.organization_id
                                AND p.cadastral_code=s.cadastral_code
              LEFT JOIN owner_parcel_relation r ON r.organization_id=s.organization_id
                                                AND r.owner_id=o.id AND r.parcel_id=p.id
             WHERE s.run_id=? AND s.organization_id=?
               AND COALESCE(r.manual_override, FALSE)=FALSE
               AND (r.owner_id IS NULL OR r.active=FALSE)
            """, runId, orgId).get("added", Integer.class);

        // Signals are generated set-wise before the relation merge, while the
        // previous active state is still visible.
        tx.execute("""
            INSERT INTO data_change_signal
                (id, organization_id, owner_id, parcel_id, signal_type, source_registry,
                 source_key, summary, payload, created_by_subject)
            SELECT gen_random_uuid(), s.organization_id, o.id, p.id, 'PORTFOLIO_OWNERSHIP_ADDED', ?,
                   s.owner_code || ':' || s.cadastral_code,
                   'Omanikuportfellis tuvastati uus aktiivne katastriüksus ' || s.cadastral_code || '.',
                   '{}'::jsonb, ?
              FROM owner_parcel_snapshot_row s
              JOIN owner o ON o.organization_id=s.organization_id
                          AND o.personal_or_registry_code=s.owner_code
              JOIN land_parcel p ON p.organization_id=s.organization_id
                                AND p.cadastral_code=s.cadastral_code
              LEFT JOIN owner_parcel_relation r ON r.organization_id=s.organization_id
                                                AND r.owner_id=o.id AND r.parcel_id=p.id
             WHERE s.run_id=? AND s.organization_id=?
               AND COALESCE(r.manual_override, FALSE)=FALSE
               AND (r.owner_id IS NULL OR r.active=FALSE)
            ON CONFLICT DO NOTHING
            """, SOURCE, SYSTEM_SUBJECT, runId, orgId);

        tx.execute("""
            INSERT INTO owner_parcel_relation
                (organization_id, owner_id, parcel_id, source, active, source_seen_at,
                 deactivated_at, updated_at)
            SELECT s.organization_id, o.id, p.id, ?, TRUE, CAST(? AS timestamptz), NULL, CAST(? AS timestamptz)
              FROM owner_parcel_snapshot_row s
              JOIN owner o ON o.organization_id=s.organization_id
                          AND o.personal_or_registry_code=s.owner_code
              JOIN land_parcel p ON p.organization_id=s.organization_id
                                AND p.cadastral_code=s.cadastral_code
             WHERE s.run_id=? AND s.organization_id=?
            ON CONFLICT (organization_id, owner_id, parcel_id) DO UPDATE
                SET source=EXCLUDED.source,
                    active=TRUE,
                    source_seen_at=EXCLUDED.source_seen_at,
                    deactivated_at=NULL,
                    updated_at=EXCLUDED.updated_at
              WHERE owner_parcel_relation.manual_override=FALSE
            """, SOURCE, seenAt, seenAt, runId, orgId);

        // A complete provider snapshot contradicts a protected active manual
        // relation when that owner/cadastre pair is absent. Record only.
        tx.execute("""
            INSERT INTO ownership_relation_conflict
                (id, organization_id, owner_id, parcel_id, source, observed_active,
                 relation_revision, status, observed_at, created_at)
            SELECT gen_random_uuid(), r.organization_id, r.owner_id, r.parcel_id, ?, FALSE,
                   r.manual_revision, 'OPEN', CAST(? AS timestamptz), CURRENT_TIMESTAMP
              FROM owner_parcel_relation r
              JOIN owner o ON o.organization_id=r.organization_id AND o.id=r.owner_id
              JOIN land_parcel p ON p.organization_id=r.organization_id AND p.id=r.parcel_id
             WHERE r.organization_id=? AND r.manual_override=TRUE AND r.active=TRUE
               AND NOT EXISTS (
                   SELECT 1 FROM owner_parcel_snapshot_row s
                    WHERE s.run_id=? AND s.organization_id=r.organization_id
                      AND s.owner_code=o.personal_or_registry_code
                      AND s.cadastral_code=p.cadastral_code
               )
            ON CONFLICT (organization_id, owner_id, parcel_id, source, observed_active, relation_revision)
            DO NOTHING
            """, SOURCE, seenAt, orgId, runId);

        int closed = tx.execute("""
            UPDATE owner_parcel_relation r
               SET active=FALSE, deactivated_at=CAST(? AS timestamptz), updated_at=CAST(? AS timestamptz)
              FROM owner o, land_parcel p
             WHERE r.organization_id=? AND r.source=? AND r.active=TRUE AND r.manual_override=FALSE
               AND o.id=r.owner_id AND o.organization_id=r.organization_id
               AND p.id=r.parcel_id AND p.organization_id=r.organization_id
               AND NOT EXISTS (
                   SELECT 1 FROM owner_parcel_snapshot_row s
                    WHERE s.run_id=? AND s.organization_id=r.organization_id
                      AND s.owner_code=o.personal_or_registry_code
                      AND s.cadastral_code=p.cadastral_code
               )
            """, seenAt, seenAt, orgId, SOURCE, runId);
        return new ApplyResult(added == null ? 0 : added, closed);
    }

    private int activeSourceRelationCount(UUID orgId) {
        return dsl.selectCount().from(table("owner_parcel_relation"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("source"), String.class).eq(SOURCE))
                .and(field(name("active"), Boolean.class).isTrue()))
            .fetchOne(0, int.class);
    }

    private void cleanupSnapshot(DSLContext tx, UUID runId) {
        tx.deleteFrom(table("owner_parcel_snapshot_row"))
            .where(field(name("run_id"), UUID.class).eq(runId)).execute();
        tx.deleteFrom(table("owner_snapshot_row"))
            .where(field(name("run_id"), UUID.class).eq(runId)).execute();
        tx.deleteFrom(table("owner_portfolio_import_stage"))
            .where(field(name("run_id"), UUID.class).eq(runId)).execute();
    }

    private void cleanupSnapshotBestEffort(UUID runId) {
        cleanupTableBestEffort("owner_parcel_snapshot_row", runId);
        cleanupTableBestEffort("owner_snapshot_row", runId);
        cleanupTableBestEffort("owner_portfolio_import_stage", runId);
    }

    private void cleanupTableBestEffort(String tableName, UUID runId) {
        try {
            dsl.deleteFrom(table(tableName))
                .where(field(name("run_id"), UUID.class).eq(runId))
                .execute();
        } catch (RuntimeException cleanupFailure) {
            log.debug("Failed to clean MetsIS staging table {} for run {}", tableName, runId, cleanupFailure);
        }
    }

    private void analyzeAfterApply() {
        if (dsl.configuration().dialect().family() != SQLDialect.POSTGRES) return;
        try {
            dsl.execute("ANALYZE owner");
            dsl.execute("ANALYZE land_parcel");
            dsl.execute("ANALYZE owner_parcel_relation");
        } catch (RuntimeException analyzeFailure) {
            // Statistics maintenance is useful after a large refresh but must not
            // turn an already committed canonical sync into a false failure.
            log.warn("MetsIS sync committed but post-sync ANALYZE failed", analyzeFailure);
        }
    }

    @Transactional(readOnly = true)
    public SyncStatus statusForCurrentOrganization() {
        UUID orgId = organizationContext.requiredOrganizationId();
        var record = dsl.fetchOne("""
            SELECT cursor_date, last_success_at, last_failure_at, last_error, last_rows_seen,
                   last_relations_added, last_relations_closed
              FROM metsis_portfolio_sync_checkpoint WHERE organization_id=?
            """, orgId);
        if (record == null) return new SyncStatus(null, null, null, null, 0, 0, 0, properties.isConfigured());
        return new SyncStatus(record.get("cursor_date", LocalDate.class), record.get("last_success_at", Instant.class),
            record.get("last_failure_at", Instant.class), record.get("last_error", String.class),
            record.get("last_rows_seen", Integer.class), record.get("last_relations_added", Integer.class),
            record.get("last_relations_closed", Integer.class), properties.isConfigured());
    }

    public List<SyncResult> syncConfiguredOrganization(String trigger) {
        if (!properties.isConfigured()) return List.of(new SyncResult(trigger, null, null, 0, 0, 0, 0, 0,
            "Omanikuportfelli ühendus ei ole seadistatud."));
        try {
            return List.of(syncOrganization(configuredOrganizationId(), trigger));
        } catch (RuntimeException exception) {
            return List.of(new SyncResult(trigger, null, null, 0, 0, 0, 0, 1,
                "Omanikuportfelli sünkroniseerimine ebaõnnestus: " + safeMessage(exception.getMessage())));
        }
    }

    public UUID configuredOrganizationId() {
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Omanikuportfelli ühendus ei ole seadistatud.");
        }
        String configured = properties.getOrganizationId();
        if (configured != null && !configured.isBlank()) {
            try {
                return UUID.fromString(configured.trim());
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "METSIS_PORTFOLIO_ORGANIZATION_ID peab olema ForestIQ organisatsiooni UUID.");
            }
        }
        var organizationId = field(name("id"), UUID.class);
        List<UUID> organizations = dsl.select(organizationId)
            .from(table("organization"))
            .limit(2)
            .fetch(organizationId);
        if (organizations.size() == 1) return organizations.getFirst();
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "METSIS_PORTFOLIO_ORGANIZATION_ID tuleb seadistada, kui ForestIQ-s ei ole täpselt üks organisatsioon.");
    }

    private void upsertCheckpoint(DSLContext tx, UUID orgId, LocalDate cursorDate, int rows, int added, int closed, String error) {
        tx.insertInto(table("metsis_portfolio_sync_checkpoint"))
            .columns(field("organization_id"), field("cursor_date"), field("last_success_at"),
                field("last_failure_at"), field("last_error"), field("last_rows_seen"),
                field("last_relations_added"), field("last_relations_closed"), field("updated_at"))
            .values(orgId, cursorDate, Instant.now(), null, error, rows, added, closed, Instant.now())
            .onConflict(field("organization_id")).doUpdate()
            .set(field("cursor_date"), cursorDate).set(field("last_success_at"), Instant.now())
            .set(field("last_failure_at"), (Instant) null).set(field("last_error"), error)
            .set(field("last_rows_seen"), rows).set(field("last_relations_added"), added)
            .set(field("last_relations_closed"), closed).set(field("updated_at"), Instant.now()).execute();
    }

    private void persistFailureCheckpoint(UUID orgId, RuntimeException original) {
        try {
            dsl.transaction(configuration -> upsertCheckpointFailure(configuration.dsl(), orgId, original.getMessage()));
        } catch (RuntimeException checkpointFailure) {
            log.error("Failed to persist MetsIS failure checkpoint for organization {}", orgId, checkpointFailure);
        }
    }

    private void upsertCheckpointFailure(DSLContext tx, UUID orgId, String error) {
        Instant now = Instant.now();
        String safeError = safeMessage(error);
        int updated = tx.update(table("metsis_portfolio_sync_checkpoint"))
            .set(field(name("last_failure_at")), now)
            .set(field(name("last_error")), safeError)
            .set(field(name("updated_at")), now)
            .where(field(name("organization_id"), UUID.class).eq(orgId))
            .execute();
        if (updated == 0) {
            tx.insertInto(table("metsis_portfolio_sync_checkpoint"))
                .columns(field(name("organization_id")), field(name("last_failure_at")),
                    field(name("last_error")), field(name("updated_at")))
                .values(orgId, now, safeError, now)
                .execute();
        }
    }

    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) return "Omanikuportfelli teenus ei vastanud";
        String safe = value.replaceAll("[\\r\\n]+", " ");
        return safe.substring(0, Math.min(500, safe.length()));
    }

    private record PortfolioStageRow(long sourceRowNumber, String ownerCode, String ownerName,
                                     String ownerType, String cadastralCode) { }
    private record SnapshotSummary(int pages, int rawRows) { }
    private record ApplyResult(int added, int closed) { }
    public record SyncResult(String trigger, LocalDate from, LocalDate to, int pages, int rows,
                             int relationsAdded, int relationsClosed, int failed, String message) { }
    public record SyncStatus(LocalDate cursorDate, Instant lastSuccessAt, Instant lastFailureAt,
                             String lastError, Integer lastRowsSeen, Integer lastRelationsAdded,
                             Integer lastRelationsClosed, boolean configured) { }
}
