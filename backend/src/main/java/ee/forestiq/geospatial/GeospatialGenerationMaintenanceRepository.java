package ee.forestiq.geospatial;

import java.time.LocalDate;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Small companion for ingestion-time metadata that is only known after a bounded streamed import. */
@Repository
public class GeospatialGenerationMaintenanceRepository {
    private final DSLContext dsl;

    public GeospatialGenerationMaintenanceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void updateStagingMetadata(UUID generationId,
                                      UUID runId,
                                      LocalDate sourceDate,
                                      String sourceVersion,
                                      String checksum,
                                      long bytes,
                                      String schemaFingerprint) {
        if (checksum == null || checksum.isBlank() || bytes <= 0) {
            throw new IllegalArgumentException("Verified checksum and positive byte count are required");
        }
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            int generationUpdated = tx.execute("""
                UPDATE geospatial_generation
                   SET source_date = ?, source_version = ?, artifact_checksum = ?, artifact_bytes = ?,
                       schema_fingerprint = ?
                 WHERE id = ? AND run_id = ? AND status = 'STAGING'
                """, sourceDate, sourceVersion, checksum, bytes, schemaFingerprint, generationId, runId);
            if (generationUpdated != 1) {
                throw new IllegalStateException("Only a STAGING generation can receive final artifact metadata");
            }
            int runUpdated = tx.execute("""
                UPDATE geospatial_source_run
                   SET source_date = ?, source_version = ?, artifact_checksum = ?, artifact_bytes = ?,
                       schema_fingerprint = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """, sourceDate, sourceVersion, checksum, bytes, schemaFingerprint, runId);
            if (runUpdated != 1) {
                throw new IllegalStateException("Only a RUNNING source run can receive final artifact metadata");
            }
        });
    }

    /**
     * Removes a not-yet-published generation after its checksum proves it is identical to an
     * already accepted generation. The run remains RUNNING and can then finish as a successful
     * unchanged source check.
     */
    public void discardDuplicateStagingGeneration(UUID generationId, UUID runId) {
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            var generation = tx.fetchOne("""
                SELECT status FROM geospatial_generation WHERE id = ? AND run_id = ? FOR UPDATE
                """, generationId, runId);
            if (generation == null || !"STAGING".equals(generation.get("status", String.class))) {
                throw new IllegalStateException("Only the run's STAGING generation can be discarded as unchanged");
            }
            tx.execute("DELETE FROM geospatial_generation_event WHERE generation_id = ?", generationId);
            tx.execute("DELETE FROM geospatial_generation WHERE id = ?", generationId);
        });
    }

    public String currentSchemaFingerprint(String sourceKey) {
        var record = dsl.fetchOne("""
            SELECT g.schema_fingerprint
              FROM geospatial_source_current c
              JOIN geospatial_generation g ON g.id = c.generation_id
             WHERE c.source_key = ?
            """, sourceKey);
        return record == null ? null : record.get("schema_fingerprint", String.class);
    }

    public LocalDate currentSourceDate(String sourceKey) {
        var record = dsl.fetchOne("""
            SELECT g.source_date
              FROM geospatial_source_current c
              JOIN geospatial_generation g ON g.id = c.generation_id
             WHERE c.source_key = ?
            """, sourceKey);
        return record == null ? null : record.get("source_date", LocalDate.class);
    }
}
