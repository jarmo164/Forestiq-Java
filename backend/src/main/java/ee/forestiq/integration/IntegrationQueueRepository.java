package ee.forestiq.integration;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed queue for integration runs.
 *
 * Claiming is atomic and uses SKIP LOCKED so multiple worker replicas can drain
 * the same queue without duplicate ownership or a central broker dependency.
 */
@Repository
class IntegrationQueueRepository {
    private final DSLContext dsl;
    private final String workerId;
    private final Duration leaseDuration;

    IntegrationQueueRepository(DSLContext dsl) {
        this(dsl, 300L, defaultWorkerId());
    }

    @Autowired
    IntegrationQueueRepository(DSLContext dsl,
                               @Value("${forestiq.runtime.worker-lease-seconds:300}") long leaseSeconds,
                               @Value("${forestiq.runtime.worker-id:}") String configuredWorkerId) {
        this.dsl = dsl;
        this.leaseDuration = Duration.ofSeconds(Math.max(30L, Math.min(leaseSeconds, 3600L)));
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
            ? defaultWorkerId()
            : configuredWorkerId;
    }

    Optional<ClaimedRun> claimNext() {
        return claimNext(null);
    }

    Optional<ClaimedRun> claimNext(Collection<String> jobKeys) {
        if (jobKeys != null && jobKeys.isEmpty()) return Optional.empty();

        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plus(leaseDuration);
        String keyFilter = jobKeys == null ? "" : "AND run.job_key = ANY(?::text[])";
        String sql = """
            WITH next_run AS (
                SELECT run.id
                  FROM integration_sync_run run
                  JOIN integration_job_ownership owner
                    ON owner.job_key = run.job_key
                   AND owner.worker_type = 'java-api-worker'
                 WHERE (run.state = 'QUEUED'
                    OR (
                        run.state IN ('RUNNING', 'VALIDATING', 'PUBLISHING')
                        AND run.lease_expires_at IS NOT NULL
                        AND run.lease_expires_at < ?::timestamptz
                    ))
                   %s
                 ORDER BY run.queued_at ASC, run.id ASC
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
            )
            UPDATE integration_sync_run run
               SET state = 'RUNNING',
                   started_at = COALESCE(run.started_at, ?::timestamptz),
                   heartbeat_at = ?::timestamptz,
                   worker_id = ?,
                   lease_expires_at = ?::timestamptz,
                   attempts = run.attempts + 1
              FROM next_run
             WHERE run.id = next_run.id
            RETURNING run.id, run.organization_id, run.job_key, run.parameters::text AS parameters
            """.formatted(keyFilter);

        Object[] bindings = jobKeys == null
            ? new Object[] {now, now, now, workerId, leaseExpiresAt}
            : new Object[] {now, jobKeys.toArray(String[]::new), now, now, workerId, leaseExpiresAt};
        var record = dsl.fetchOne(sql, bindings);
        if (record == null) return Optional.empty();
        return Optional.of(new ClaimedRun(
            record.get("id", UUID.class),
            record.get("organization_id", UUID.class),
            record.get("job_key", String.class),
            record.get("parameters", String.class)));
    }

    boolean heartbeat(UUID id) {
        Instant now = Instant.now();
        return dsl.update(table("integration_sync_run"))
            .set(field("heartbeat_at", Instant.class), now)
            .set(field("lease_expires_at", Instant.class), now.plus(leaseDuration))
            .where(field("id", UUID.class).eq(id)
                .and(field("worker_id", String.class).eq(workerId))
                .and(field("state", String.class).in("RUNNING", "VALIDATING", "PUBLISHING"))
                .and(field("finished_at", Instant.class).isNull()))
            .execute() == 1;
    }

    /**
     * Returns a claim to PostgreSQL when the local worker executor cannot accept
     * it. The state transition is deliberately conditional so a completed or
     * otherwise-owned run can never be resurrected accidentally.
     */
    boolean releaseClaim(UUID id) {
        return dsl.update(table("integration_sync_run"))
            .set(field("state", String.class), "QUEUED")
            .set(field("started_at", Instant.class), (Instant) null)
            .set(field("heartbeat_at", Instant.class), (Instant) null)
            .set(field("worker_id", String.class), (String) null)
            .set(field("lease_expires_at", Instant.class), (Instant) null)
            .where(field("id", UUID.class).eq(id)
                .and(field("worker_id", String.class).eq(workerId))
                .and(field("state", String.class).eq("RUNNING"))
                .and(field("finished_at", Instant.class).isNull()))
            .execute() == 1;
    }

    long queuedCount() {
        return stateCount("QUEUED");
    }

    long runningCount() {
        return dsl.selectCount()
            .from(table("integration_sync_run"))
            .where(field("state", String.class).in("RUNNING", "VALIDATING", "PUBLISHING"))
            .fetchOne(0, long.class);
    }

    private long stateCount(String state) {
        return dsl.selectCount()
            .from(table("integration_sync_run"))
            .where(field("state", String.class).eq(state))
            .fetchOne(0, long.class);
    }

    private static String defaultWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "forestiq-worker";
        }
    }

    record ClaimedRun(UUID id, UUID organizationId, String jobKey, String parameters) { }
}
