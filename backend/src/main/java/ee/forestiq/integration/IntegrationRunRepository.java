package ee.forestiq.integration;

import static org.jooq.impl.DSL.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
class IntegrationRunRepository {
    private static final Duration STALE_QUEUED_AFTER = Duration.ofMinutes(15);
    private static final Duration STALE_RUNNING_AFTER = Duration.ofHours(12);
    private static final Duration STALE_LEASE_AFTER = Duration.ofMinutes(5);

    private final DSLContext dsl;

    IntegrationRunRepository(DSLContext dsl) { this.dsl = dsl; }

    IntegrationJobService.RunView create(UUID orgId, IntegrationJobKey key, String trigger, String subject,
                                         String parametersJson) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        dsl.insertInto(table("integration_sync_run"))
            .columns(field("id"), field("organization_id"), field("job_key"), field("trigger_type"),
                field("requested_by_subject"), field("state"), field("parameters"), field("queued_at"))
            .values(id, orgId, key.name(), trigger, subject, "QUEUED", JSONB.valueOf(parametersJson), now)
            .execute();
        return get(orgId, id);
    }

    boolean markRunning(UUID id) {
        return dsl.update(table("integration_sync_run"))
            .set(field("state"), "RUNNING")
            .set(field("started_at"), Instant.now())
            .set(field("heartbeat_at"), Instant.now())
            .set(field("lease_expires_at"), Instant.now().plus(STALE_LEASE_AFTER))
            .where(field("id", UUID.class).eq(id).and(field("state", String.class).eq("QUEUED")))
            .execute() == 1;
    }

    void complete(UUID id, String state, String metricsJson, String message, String error) {
        dsl.update(table("integration_sync_run"))
            .set(field("state"), state)
            .set(field("metrics"), JSONB.valueOf(metricsJson))
            .set(field("message"), truncate(message))
            .set(field("error_summary"), truncate(error))
            .set(field("finished_at"), Instant.now())
            .set(field("heartbeat_at"), Instant.now())
            .set(field("lease_expires_at"), (Instant) null)
            .where(field("id", UUID.class).eq(id))
            .execute();
    }

    /**
     * Releases durable run rows left active by a terminated application process.
     * The caller must first acquire the same distributed advisory lock used by
     * the job, which proves that no live worker still owns the run.
     */
    int expireStaleActive(UUID orgId, IntegrationJobKey key) {
        Instant now = Instant.now();
        Instant queuedBefore = now.minus(STALE_QUEUED_AFTER);
        Instant runningBefore = now.minus(STALE_RUNNING_AFTER);
        return dsl.execute("""
            UPDATE integration_sync_run
               SET state='FAILED',
                   message='Eelmine sünkroonimine aegus enne lõpetamist; järgmine jooks võib turvaliselt jätkata.',
                   error_summary='Stale integration run recovered automatically',
                   finished_at=?::timestamptz
             WHERE organization_id=? AND job_key=?
               AND ((state='QUEUED' AND queued_at < ?::timestamptz)
                 OR (state IN ('RUNNING','VALIDATING','PUBLISHING')
                     AND COALESCE(lease_expires_at, started_at, queued_at) < ?::timestamptz))
            """, now, orgId, key.name(), queuedBefore, runningBefore);
    }

    IntegrationJobService.RunView get(UUID orgId, UUID id) {
        var record = dsl.fetchOne("""
            SELECT id, organization_id, job_key, trigger_type, requested_by_subject, state,
                   parameters::text parameters, metrics::text metrics, message, error_summary,
                   queued_at, started_at, finished_at
            FROM integration_sync_run WHERE organization_id=? AND id=?
            """, orgId, id);
        return record == null ? null : map(record);
    }

    IntegrationJobService.RunView active(UUID orgId, IntegrationJobKey key) {
        var record = dsl.fetchOne("""
            SELECT id, organization_id, job_key, trigger_type, requested_by_subject, state,
                   parameters::text parameters, metrics::text metrics, message, error_summary,
                   queued_at, started_at, finished_at
            FROM integration_sync_run
            WHERE organization_id=? AND job_key=? AND state IN ('QUEUED','RUNNING','VALIDATING','PUBLISHING')
            ORDER BY queued_at DESC LIMIT 1
            """, orgId, key.name());
        return record == null ? null : map(record);
    }

    IntegrationJobService.RunView latest(UUID orgId, IntegrationJobKey key) {
        var record = dsl.fetchOne("""
            SELECT id, organization_id, job_key, trigger_type, requested_by_subject, state,
                   parameters::text parameters, metrics::text metrics, message, error_summary,
                   queued_at, started_at, finished_at
            FROM integration_sync_run WHERE organization_id=? AND job_key=?
            ORDER BY queued_at DESC LIMIT 1
            """, orgId, key.name());
        return record == null ? null : map(record);
    }

    IntegrationJobService.RunView latestSuccess(UUID orgId, IntegrationJobKey key) {
        var record = dsl.fetchOne("""
            SELECT id, organization_id, job_key, trigger_type, requested_by_subject, state,
                   parameters::text parameters, metrics::text metrics, message, error_summary,
                   queued_at, started_at, finished_at
            FROM integration_sync_run
            WHERE organization_id=? AND job_key=? AND state='SUCCEEDED'
            ORDER BY finished_at DESC NULLS LAST LIMIT 1
            """, orgId, key.name());
        return record == null ? null : map(record);
    }

    List<IntegrationJobService.RunView> recent(UUID orgId, IntegrationJobKey key, int limit) {
        return dsl.fetch("""
            SELECT id, organization_id, job_key, trigger_type, requested_by_subject, state,
                   parameters::text parameters, metrics::text metrics, message, error_summary,
                   queued_at, started_at, finished_at
            FROM integration_sync_run WHERE organization_id=? AND job_key=?
            ORDER BY queued_at DESC LIMIT ?
            """, orgId, key.name(), Math.max(1, Math.min(limit, 50))).map(IntegrationRunRepository::map);
    }

    int failureStreak(UUID orgId, IntegrationJobKey key) {
        List<String> states = dsl.select(field("state", String.class))
            .from(table("integration_sync_run"))
            .where(field("organization_id", UUID.class).eq(orgId)
                .and(field("job_key", String.class).eq(key.name()))
                .and(field("state", String.class).notIn("QUEUED", "RUNNING")))
            .orderBy(field("queued_at").desc())
            .limit(50)
            .fetch(field("state", String.class));
        int streak = 0;
        for (String state : states) {
            if ("SUCCEEDED".equals(state)) break;
            if ("FAILED".equals(state) || "PARTIAL".equals(state)) streak++;
        }
        return streak;
    }

    boolean durableBacklog(UUID orgId, IntegrationJobKey key) {
        return switch (key) {
            case FOREST_NOTIFICATIONS -> registryCheckpointExists(orgId, "METSAREGISTER_TEATISED");
            case FOREST_PLAN -> registryCheckpointExists(orgId, "METSAREGISTER_ERALDISED");
            case WEASEL_OWNERSHIP_TRANSITIONS -> booleanQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM weasel_transition_sync_checkpoint
                    WHERE organization_id=? AND (window_end IS NOT NULL OR next_page IS NOT NULL)
                )
                """, orgId);
            case INHERITANCE_NOTICES -> booleanQuery("""
                SELECT COALESCE((metrics->>'catchupRemaining')::boolean, false)
                  FROM integration_sync_run
                 WHERE organization_id=? AND job_key=?
                   AND state IN ('SUCCEEDED','PARTIAL')
                 ORDER BY finished_at DESC NULLS LAST, queued_at DESC
                 LIMIT 1
                """, orgId, key.name());
            default -> false;
        };
    }

    Long cursorLagSeconds(UUID orgId, IntegrationJobKey key) {
        if (key == IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS) {
            Instant cursor = dsl.select(field("cursor_time", Instant.class))
                .from(table("weasel_transition_sync_checkpoint"))
                .where(field("organization_id", UUID.class).eq(orgId))
                .fetchOne(field("cursor_time", Instant.class));
            return cursor == null ? null : Math.max(0L, Duration.between(cursor, Instant.now()).toSeconds());
        }
        if (key == IntegrationJobKey.INHERITANCE_NOTICES) {
            Object raw = dsl.fetchValue("""
                SELECT metrics->>'lastCompletedDate'
                  FROM integration_sync_run
                 WHERE organization_id=? AND job_key=?
                   AND state IN ('SUCCEEDED','PARTIAL')
                   AND metrics->>'lastCompletedDate' IS NOT NULL
                 ORDER BY finished_at DESC NULLS LAST, queued_at DESC
                 LIMIT 1
                """, orgId, key.name());
            if (raw == null) return null;
            try {
                LocalDate completed = LocalDate.parse(String.valueOf(raw));
                LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
                return Math.max(0L, ChronoUnit.DAYS.between(completed, yesterday)) * 86_400L;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    List<UUID> organizations() {
        return dsl.select(field("id", UUID.class)).from(table("organization")).fetch(field("id", UUID.class));
    }

    Long worstLatestSuccessAgeSeconds(IntegrationJobKey key) {
        var record = dsl.fetchOne("""
            SELECT min(last_success)
              FROM (
                    SELECT organization_id, max(finished_at) AS last_success
                      FROM integration_sync_run
                     WHERE job_key=? AND state='SUCCEEDED' AND finished_at IS NOT NULL
                     GROUP BY organization_id
                   ) latest
            """, key.name());
        Instant oldestLatestSuccess = record == null ? null : record.get(0, Instant.class);
        return oldestLatestSuccess == null ? null
            : Math.max(0L, Duration.between(oldestLatestSuccess, Instant.now()).toSeconds());
    }

    int worstFailureStreak(IntegrationJobKey key) {
        int worst = 0;
        for (UUID orgId : organizations()) worst = Math.max(worst, failureStreak(orgId, key));
        return worst;
    }

    boolean anyBacklog(IntegrationJobKey key) {
        for (UUID orgId : organizations()) {
            if (durableBacklog(orgId, key)) return true;
            IntegrationJobService.RunView latest = latest(orgId, key);
            if (latest != null && metricTrue(latest.metrics(), "backlogRemaining", "catchupRemaining")) return true;
        }
        return false;
    }

    Long worstCursorLagSeconds(IntegrationJobKey key) {
        Long worst = null;
        for (UUID orgId : organizations()) {
            Long lag = cursorLagSeconds(orgId, key);
            if (lag != null && (worst == null || lag > worst)) worst = lag;
        }
        return worst;
    }

    private boolean registryCheckpointExists(UUID orgId, String registryName) {
        return booleanQuery("""
            SELECT EXISTS (
                SELECT 1 FROM registry_bulk_sync_checkpoint
                WHERE organization_id=? AND registry_name=?
            )
            """, orgId, registryName);
    }

    private boolean booleanQuery(String sql, Object... bindings) {
        var record = dsl.fetchOne(sql, bindings);
        return record != null && Boolean.TRUE.equals(record.get(0, Boolean.class));
    }

    private static boolean metricTrue(String metrics, String... keys) {
        if (metrics == null || metrics.isBlank()) return false;
        String compact = metrics.replaceAll("\\s+", "");
        for (String key : keys) {
            if (compact.contains("\"" + key + "\":true")) return true;
        }
        return false;
    }

    private static IntegrationJobService.RunView map(org.jooq.Record record) {
        return new IntegrationJobService.RunView(
            record.get("id", UUID.class), record.get("job_key", String.class),
            record.get("trigger_type", String.class), record.get("state", String.class),
            record.get("requested_by_subject", String.class), record.get("parameters", String.class),
            record.get("metrics", String.class), record.get("message", String.class),
            record.get("error_summary", String.class), record.get("queued_at", Instant.class),
            record.get("started_at", Instant.class), record.get("finished_at", Instant.class));
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("[\\r\\n]+", " ").trim();
        return safe.substring(0, Math.min(500, safe.length()));
    }
}
