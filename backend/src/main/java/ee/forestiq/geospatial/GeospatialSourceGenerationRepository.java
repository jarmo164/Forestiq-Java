package ee.forestiq.geospatial;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Owns the source/run/generation state transitions shared by cadastre, ETAK and
 * administrative geospatial datasets.
 *
 * <p>Publication and rollback deliberately lock the source row first. This makes
 * one source the serialization boundary across API/worker replicas without making
 * an external cache or process-local mutex a correctness dependency.</p>
 */
@Repository
public class GeospatialSourceGenerationRepository {

    private static final Set<String> FAILURE_OUTCOMES = Set.of("PARTIAL", "FAILED", "CANCELLED");

    private final DSLContext dsl;

    public GeospatialSourceGenerationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID startRun(String sourceKey, String triggerType) {
        UUID runId = UUID.randomUUID();
        int inserted = dsl.execute("""
            INSERT INTO geospatial_source_run
                (id, source_key, trigger_type, status, started_at)
            SELECT ?, source_key, ?, 'RUNNING', CURRENT_TIMESTAMP
              FROM geospatial_source
             WHERE source_key = ?
            """, runId, triggerType, sourceKey);
        if (inserted != 1) {
            throw new IllegalArgumentException("Unknown geospatial source: " + sourceKey);
        }
        return runId;
    }

    /** Records the verified artifact metadata before an importer creates a staging generation. */
    public void recordDownloadedArtifact(UUID runId,
                                         String sourceVersion,
                                         String artifactChecksum,
                                         long artifactBytes) {
        if (artifactChecksum == null || artifactChecksum.isBlank()) {
            throw new IllegalArgumentException("Artifact checksum is required");
        }
        if (artifactBytes <= 0) {
            throw new IllegalArgumentException("Artifact bytes must be positive");
        }
        int updated = dsl.execute("""
            UPDATE geospatial_source_run
               SET source_version = ?, artifact_checksum = ?, artifact_bytes = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'RUNNING'
            """, sourceVersion, artifactChecksum, artifactBytes, runId);
        if (updated != 1) {
            throw new IllegalStateException("Only a RUNNING geospatial source run can record an artifact");
        }
    }

    /** Completes a successful source check that found no new publishable artifact. */
    public void finishSuccessfulCheck(UUID runId) {
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            var run = tx.fetchOne("""
                SELECT status, artifact_checksum
                  FROM geospatial_source_run
                 WHERE id = ?
                 FOR UPDATE
                """, runId);
            if (run == null) {
                throw new IllegalArgumentException("Unknown geospatial run: " + runId);
            }
            if (!"RUNNING".equals(run.get("status", String.class))) {
                throw new IllegalStateException("Only a RUNNING source check can complete successfully");
            }
            if (run.get("artifact_checksum", String.class) == null) {
                throw new IllegalStateException("Successful source check requires verified artifact metadata");
            }
            if (tx.fetchExists(
                tx.selectOne().from("geospatial_generation").where("run_id = ?", runId))) {
                throw new IllegalStateException(
                    "A run with a generation must complete through generation publication, not source-check completion");
            }
            tx.execute("""
                UPDATE geospatial_source_run
                   SET status = 'SUCCESS', completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP, error_summary = NULL
                 WHERE id = ?
                """, runId);
        });
    }

    public UUID createStagingGeneration(UUID runId,
                                        String sourceKey,
                                        LocalDate sourceDate,
                                        String sourceVersion,
                                        String artifactChecksum,
                                        Long artifactBytes,
                                        String schemaFingerprint,
                                        Integer sourceSrid,
                                        int canonicalSrid) {
        UUID generationId = UUID.randomUUID();
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            int inserted = tx.execute("""
                INSERT INTO geospatial_generation
                    (id, source_key, run_id, status, source_date, source_version,
                     artifact_checksum, artifact_bytes, schema_fingerprint,
                     source_srid, canonical_srid)
                SELECT ?, r.source_key, r.id, 'STAGING', ?, ?, ?, ?, ?, ?, ?
                  FROM geospatial_source_run r
                 WHERE r.id = ?
                   AND r.source_key = ?
                   AND r.status = 'RUNNING'
                """, generationId, sourceDate, sourceVersion, artifactChecksum,
                artifactBytes, schemaFingerprint, sourceSrid, canonicalSrid,
                runId, sourceKey);
            if (inserted != 1) {
                throw new IllegalStateException(
                    "Active geospatial run does not match source: " + sourceKey);
            }
            tx.execute("""
                UPDATE geospatial_source_run
                   SET source_date = ?, source_version = ?, artifact_checksum = ?,
                       artifact_bytes = ?, schema_fingerprint = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, sourceDate, sourceVersion, artifactChecksum, artifactBytes,
                schemaFingerprint, runId);
            tx.execute("""
                INSERT INTO geospatial_generation_event
                    (source_key, generation_id, run_id, event_type, reason)
                VALUES (?, ?, ?, 'CREATED', 'Staging generation created')
                """, sourceKey, generationId, runId);
        });
        return generationId;
    }

    public void markValidated(UUID generationId,
                              long rowCount,
                              long rejectedRows,
                              long repairedGeometries,
                              String validationSummaryJson) {
        if (rowCount < 0 || rejectedRows < 0 || repairedGeometries < 0) {
            throw new IllegalArgumentException("Generation counters must be non-negative");
        }
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            var generation = tx.fetchOne("""
                SELECT source_key, run_id, status
                  FROM geospatial_generation
                 WHERE id = ?
                 FOR UPDATE
                """, generationId);
            if (generation == null) {
                throw new IllegalArgumentException("Unknown geospatial generation: " + generationId);
            }
            String status = generation.get("status", String.class);
            if (!"STAGING".equals(status)) {
                throw new IllegalStateException(
                    "Only STAGING generation can be validated; current status=" + status);
            }
            String sourceKey = generation.get("source_key", String.class);
            UUID runId = generation.get("run_id", UUID.class);
            tx.execute("""
                UPDATE geospatial_generation
                   SET status = 'VALIDATED', row_count = ?, rejected_row_count = ?,
                       repaired_geometry_count = ?, validation_summary = ?::jsonb,
                       validated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, rowCount, rejectedRows, repairedGeometries,
                validationSummaryJson == null || validationSummaryJson.isBlank()
                    ? "{}" : validationSummaryJson,
                generationId);
            tx.execute("""
                UPDATE geospatial_source_run
                   SET rows_staged = ?, rows_rejected = ?, geometries_repaired = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """, rowCount, rejectedRows, repairedGeometries, runId);
            tx.execute("""
                INSERT INTO geospatial_generation_event
                    (source_key, generation_id, run_id, event_type, reason)
                VALUES (?, ?, ?, 'VALIDATED', 'Generation validation completed')
                """, sourceKey, generationId, runId);
        });
    }

    /**
     * Completes an unsuccessful/partial run without publishing any generation.
     *
     * <p>A PARTIAL run is a first-class operational outcome, but any staging/validated
     * generation attached to it becomes FAILED because partial national source data is
     * not a publishable canonical generation.</p>
     */
    public void finishUnpublishedRun(UUID runId,
                                     String outcome,
                                     RunCounters counters,
                                     String errorSummary) {
        if (!FAILURE_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException(
                "Unpublished run outcome must be PARTIAL, FAILED or CANCELLED");
        }
        RunCounters safeCounters = counters == null ? RunCounters.zero() : counters;
        safeCounters.validate();

        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            var run = tx.fetchOne("""
                SELECT source_key, status
                  FROM geospatial_source_run
                 WHERE id = ?
                 FOR UPDATE
                """, runId);
            if (run == null) {
                throw new IllegalArgumentException("Unknown geospatial run: " + runId);
            }
            String currentStatus = run.get("status", String.class);
            if (!"RUNNING".equals(currentStatus) && !"QUEUED".equals(currentStatus)) {
                throw new IllegalStateException(
                    "Only active geospatial run can be completed; current status=" + currentStatus);
            }
            String sourceKey = run.get("source_key", String.class);
            String safeError = boundedError(errorSummary);

            tx.execute("""
                UPDATE geospatial_source_run
                   SET status = ?, rows_read = ?, rows_staged = ?, rows_inserted = ?,
                       rows_updated = ?, rows_unchanged = ?, rows_removed = ?,
                       rows_rejected = ?, geometries_repaired = ?, error_summary = ?,
                       completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, outcome,
                safeCounters.rowsRead(), safeCounters.rowsStaged(), safeCounters.rowsInserted(),
                safeCounters.rowsUpdated(), safeCounters.rowsUnchanged(), safeCounters.rowsRemoved(),
                safeCounters.rowsRejected(), safeCounters.geometriesRepaired(), safeError,
                runId);

            var generation = tx.fetchOne("""
                SELECT id, status
                  FROM geospatial_generation
                 WHERE run_id = ?
                 FOR UPDATE
                """, runId);
            if (generation != null) {
                UUID generationId = generation.get("id", UUID.class);
                String generationStatus = generation.get("status", String.class);
                if ("STAGING".equals(generationStatus) || "VALIDATED".equals(generationStatus)) {
                    tx.execute("""
                        UPDATE geospatial_generation
                           SET status = 'FAILED', failed_at = CURRENT_TIMESTAMP,
                               failure_summary = ?
                         WHERE id = ?
                        """, safeError, generationId);
                    tx.execute("""
                        INSERT INTO geospatial_generation_event
                            (source_key, generation_id, run_id, event_type, reason)
                        VALUES (?, ?, ?, 'FAILED', ?)
                        """, sourceKey, generationId, runId, safeError);
                }
            }
        });
    }

    /** Publishes one validated generation and retires the previous current generation atomically. */
    public void publishValidated(String sourceKey,
                                 UUID generationId,
                                 String actorSubject,
                                 String reason) {
        changeCurrent(sourceKey, generationId, actorSubject, reason, false);
    }

    /**
     * Rolls a source back to a previously retired generation atomically.
     * No provider download or domain-row reconstruction is involved.
     */
    public void rollbackToRetired(String sourceKey,
                                  UUID generationId,
                                  String actorSubject,
                                  String reason) {
        changeCurrent(sourceKey, generationId, actorSubject, reason, true);
    }

    public UUID currentGenerationId(String sourceKey) {
        var record = dsl.fetchOne("""
            SELECT generation_id
              FROM geospatial_source_current
             WHERE source_key = ?
            """, sourceKey);
        return record == null ? null : record.get("generation_id", UUID.class);
    }

    /** Used by snapshot downloaders to avoid re-importing an already accepted artifact. */
    public boolean hasAcceptedArtifactChecksum(String sourceKey, String artifactChecksum) {
        if (artifactChecksum == null || artifactChecksum.isBlank()) return false;
        return dsl.fetchExists(
            dsl.selectOne()
                .from("geospatial_generation")
                .where("source_key = ?", sourceKey)
                .and("artifact_checksum = ?", artifactChecksum)
                .and("status IN ('VALIDATED', 'PUBLISHED', 'RETIRED')"));
    }

    private void changeCurrent(String sourceKey,
                               UUID generationId,
                               String actorSubject,
                               String reason,
                               boolean rollback) {
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();

            // The source row is the cross-replica serialization lock for publication.
            var source = tx.fetchOne("""
                SELECT source_key
                  FROM geospatial_source
                 WHERE source_key = ?
                 FOR UPDATE
                """, sourceKey);
            if (source == null) {
                throw new IllegalArgumentException("Unknown geospatial source: " + sourceKey);
            }

            var target = tx.fetchOne("""
                SELECT status, run_id
                  FROM geospatial_generation
                 WHERE source_key = ? AND id = ?
                 FOR UPDATE
                """, sourceKey, generationId);
            if (target == null) {
                throw new IllegalArgumentException(
                    "Generation does not belong to source " + sourceKey + ": " + generationId);
            }

            String targetStatus = target.get("status", String.class);
            String expectedStatus = rollback ? "RETIRED" : "VALIDATED";
            if (!expectedStatus.equals(targetStatus)) {
                throw new IllegalStateException(
                    (rollback ? "Rollback target must be RETIRED" : "Publish target must be VALIDATED")
                        + "; current status=" + targetStatus);
            }
            UUID targetRunId = target.get("run_id", UUID.class);

            var current = tx.fetchOne("""
                SELECT generation_id
                  FROM geospatial_source_current
                 WHERE source_key = ?
                 FOR UPDATE
                """, sourceKey);
            UUID previousGenerationId = current == null
                ? null
                : current.get("generation_id", UUID.class);

            if (previousGenerationId != null && !previousGenerationId.equals(generationId)) {
                tx.execute("""
                    UPDATE geospatial_generation
                       SET status = 'RETIRED', retired_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND source_key = ? AND status = 'PUBLISHED'
                    """, previousGenerationId, sourceKey);
                tx.execute("""
                    INSERT INTO geospatial_generation_event
                        (source_key, generation_id, event_type, actor_subject, reason)
                    VALUES (?, ?, 'RETIRED', ?, ?)
                    """, sourceKey, previousGenerationId, actorSubject,
                    boundedReason(rollback ? "Retired by rollback cutover" : "Retired by new publication"));
            }

            tx.execute("""
                UPDATE geospatial_generation
                   SET status = 'PUBLISHED',
                       published_at = COALESCE(published_at, CURRENT_TIMESTAMP),
                       retired_at = NULL
                 WHERE id = ? AND source_key = ?
                """, generationId, sourceKey);

            tx.execute("""
                INSERT INTO geospatial_source_current (source_key, generation_id, published_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (source_key) DO UPDATE SET
                    generation_id = EXCLUDED.generation_id,
                    published_at = EXCLUDED.published_at
                """, sourceKey, generationId);

            tx.execute("""
                INSERT INTO geospatial_generation_event
                    (source_key, generation_id, run_id, event_type, actor_subject, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sourceKey, generationId, targetRunId,
                rollback ? "ROLLED_BACK" : "PUBLISHED",
                actorSubject, boundedReason(reason));

            if (!rollback) {
                tx.execute("""
                    UPDATE geospatial_source_run
                       SET status = 'SUCCESS', completed_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP, error_summary = NULL
                     WHERE id = ? AND status = 'RUNNING'
                    """, targetRunId);
            }
        });
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String clean = reason.replaceAll("[\\r\\n]+", " ").trim();
        return clean.substring(0, Math.min(1_000, clean.length()));
    }

    private static String boundedError(String errorSummary) {
        if (errorSummary == null || errorSummary.isBlank()) return null;
        String clean = errorSummary
            .replaceAll("(?i)(token|password|authorization|secret)\\s*[:=]\\s*[^,; ]+", "$1=[redacted]")
            .replaceAll("[\\r\\n]+", " ")
            .trim();
        return clean.substring(0, Math.min(2_000, clean.length()));
    }

    public record RunCounters(long rowsRead,
                              long rowsStaged,
                              long rowsInserted,
                              long rowsUpdated,
                              long rowsUnchanged,
                              long rowsRemoved,
                              long rowsRejected,
                              long geometriesRepaired) {
        public static RunCounters zero() {
            return new RunCounters(0, 0, 0, 0, 0, 0, 0, 0);
        }

        private void validate() {
            if (rowsRead < 0 || rowsStaged < 0 || rowsInserted < 0 || rowsUpdated < 0
                || rowsUnchanged < 0 || rowsRemoved < 0 || rowsRejected < 0
                || geometriesRepaired < 0) {
                throw new IllegalArgumentException("Run counters must be non-negative");
            }
        }
    }
}
