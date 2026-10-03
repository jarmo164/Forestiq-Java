package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
class RegistrySyncAuditRepository {

    private final DSLContext dsl;
    private final RegistryPayloadPolicy payloadPolicy;
    private final RegistryProperties properties;

    RegistrySyncAuditRepository(DSLContext dsl, RegistryPayloadPolicy payloadPolicy, RegistryProperties properties) {
        this.dsl = dsl;
        this.payloadPolicy = payloadPolicy;
        this.properties = properties;
    }

    void recordDataSignal(UUID orgId, UUID ownerId, UUID parcelId, String type, String registry, String sourceKey,
                          String summary, Object payload, String subject) {
        if (ownerId == null) return;
        String evidence = payloadPolicy.auditEnvelope(payload, "SUCCESS");
        dsl.insertInto(table("data_change_signal"))
            .columns(field(name("id")), field(name("organization_id")), field(name("owner_id")), field(name("parcel_id")),
                field(name("signal_type")), field(name("source_registry")), field(name("source_key")), field(name("summary")),
                field(name("payload")), field(name("created_by_subject")))
            .values(UUID.randomUUID(), orgId, ownerId, parcelId, type, registry, sourceKey, summary,
                field("?::jsonb", Object.class, evidence == null ? "{}" : evidence), subject)
            .onConflictDoNothing()
            .execute();
    }

    void logSync(UUID orgId, String cadastralCode, String registry, String status, int records, String error,
                 Object raw, String requestUrl, String subject) {
        try {
            String auditJson = payloadPolicy.auditEnvelope(raw, status);
            dsl.insertInto(table("registry_sync_log"))
                .columns(field(name("id")), field(name("organization_id")), field(name("cadastral_code")),
                    field(name("registry_name")), field(name("request_url")), field(name("status")),
                    field(name("records_updated")), field(name("error_message")), field(name("raw_response")),
                    field(name("synced_by_subject")))
                .values(UUID.randomUUID(), orgId, cadastralCode, registry, requestUrl, status, records, boundedError(error),
                    auditJson == null ? null : field("?::jsonb", Object.class, auditJson), subject)
                .execute();
        } catch (Exception ignored) {
            // A log-write issue must not hide successful data or the original provider error.
        }
    }

    /** Removes old rows and strips short-lived troubleshooting payloads before row retention expires. */
    CleanupResult cleanupExpired() {
        RegistryProperties.Audit audit = properties.getAudit();
        Instant now = Instant.now();
        Instant payloadCutoff = now.minus(Math.max(1, audit.getTroubleshootingPayloadRetentionHours()), ChronoUnit.HOURS);
        Instant rowCutoff = now.minus(Math.max(1, audit.getRetentionDays()), ChronoUnit.DAYS);

        int payloadsTrimmed = 0;
        try {
            payloadsTrimmed = dsl.execute("""
                UPDATE registry_sync_log
                   SET raw_response = raw_response - 'payload'
                 WHERE created_at < ?::timestamptz
                   AND raw_response IS NOT NULL
                   AND raw_response->'payload' IS NOT NULL
                """, payloadCutoff);
        } catch (RuntimeException ignored) {
            // Cleanup is best-effort; the next scheduled run retries it.
        }
        int rowsDeleted = dsl.deleteFrom(table("registry_sync_log"))
            .where(field(name("created_at"), Instant.class).lessThan(rowCutoff))
            .execute();
        return new CleanupResult(payloadsTrimmed, rowsDeleted);
    }

    long rowCount() {
        Long value = dsl.selectCount().from(table("registry_sync_log")).fetchOne(0, Long.class);
        return value == null ? 0 : value;
    }

    long largestStoredPayloadBytes() {
        try {
            var record = dsl.fetchOne("SELECT COALESCE(MAX(octet_length(raw_response::text)), 0) AS bytes FROM registry_sync_log");
            Long value = record == null ? null : record.get("bytes", Long.class);
            return value == null ? 0 : value;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    String sourceHash(Object payload) {
        return payloadPolicy.sourceHash(payload);
    }

    /** Compatibility name retained for the existing change-detection call site; value is now a deterministic hash. */
    String safeJson(Object payload) {
        return payloadPolicy.sourceHash(payload);
    }

    private static String boundedError(String error) {
        if (error == null || error.isBlank()) return null;
        String safe = error
            .replaceAll("(?i)(authorization|token|password|secret)\\s*[:=]\\s*[^,;\\s]+", "$1=[redacted]")
            .replaceAll("[\\r\\n]+", " ")
            .trim();
        return safe.substring(0, Math.min(2_000, safe.length()));
    }

    record CleanupResult(int troubleshootingPayloadsTrimmed, int rowsDeleted) { }
}
