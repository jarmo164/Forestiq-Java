package ee.forestiq.geospatial;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ee.forestiq.geospatial.etak.EtakAdminLayerManifestService;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/** Read model behind Admin → Teenused. No provider URLs or credentials are exposed. */
@Service
public class GeospatialOperationsService {
    private final DSLContext dsl;
    private final EtakAdminLayerManifestService manifest;
    private final GeospatialManualRefreshExecutor manualRefresh;

    public GeospatialOperationsService(DSLContext dsl,
                                       EtakAdminLayerManifestService manifest,
                                       GeospatialManualRefreshExecutor manualRefresh) {
        this.dsl = dsl;
        this.manifest = manifest;
        this.manualRefresh = manualRefresh;
    }

    public List<SourceStatus> sources() {
        List<Record> sources = dsl.fetch("""
            SELECT source_key, provider, dataset_key, display_name, source_kind, enabled,
                   refresh_interval_seconds, freshness_target_seconds, expected_source_srid,
                   canonical_srid
              FROM geospatial_source
             WHERE provider = 'MAA_JA_RUUMIAMET'
             ORDER BY source_kind, display_name
            """);
        List<SourceStatus> result = new ArrayList<>(sources.size());
        for (Record source : sources) result.add(status(source));
        return List.copyOf(result);
    }

    public SourceStatus source(String sourceKey) {
        Record source = dsl.fetchOne("""
            SELECT source_key, provider, dataset_key, display_name, source_kind, enabled,
                   refresh_interval_seconds, freshness_target_seconds, expected_source_srid,
                   canonical_srid
              FROM geospatial_source
             WHERE source_key = ?
            """, sourceKey);
        if (source == null) throw new IllegalArgumentException("Unknown geospatial source: " + sourceKey);
        return status(source);
    }

    private SourceStatus status(Record source) {
        String sourceKey = source.get("source_key", String.class);
        Record current = dsl.fetchOne("""
            SELECT g.id, g.source_date, g.source_version, g.artifact_checksum, g.artifact_bytes,
                   g.schema_fingerprint, g.row_count, g.published_at, c.published_at AS current_since
              FROM geospatial_source_current c
              JOIN geospatial_generation g ON g.id = c.generation_id
             WHERE c.source_key = ?
            """, sourceKey);
        Record latestRun = dsl.fetchOne("""
            SELECT id, trigger_type, status, source_date, source_version, artifact_checksum,
                   artifact_bytes, schema_fingerprint, rows_read, rows_staged, rows_inserted,
                   rows_updated, rows_unchanged, rows_removed, rows_rejected, geometries_repaired,
                   error_summary, started_at, completed_at, created_at
              FROM geospatial_source_run
             WHERE source_key = ?
             ORDER BY created_at DESC
             LIMIT 1
            """, sourceKey);
        Record latestSuccess = dsl.fetchOne("""
            SELECT id, completed_at
              FROM geospatial_source_run
             WHERE source_key = ? AND status = 'SUCCESS'
             ORDER BY completed_at DESC NULLS LAST
             LIMIT 1
            """, sourceKey);
        List<Record> recent = dsl.fetch("""
            SELECT id, trigger_type, status, rows_read, rows_staged, rows_inserted,
                   rows_rejected, artifact_bytes, error_summary, started_at, completed_at, created_at
              FROM geospatial_source_run
             WHERE source_key = ?
             ORDER BY created_at DESC
             LIMIT 5
            """, sourceKey);

        Long freshnessTarget = number(source, "freshness_target_seconds");
        OffsetDateTime lastSuccessAt = time(latestSuccess, "completed_at");
        long ageSeconds = lastSuccessAt == null ? -1 : Math.max(0,
            Duration.between(lastSuccessAt, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds());
        String freshness = freshness(ageSeconds, freshnessTarget, latestRun);
        boolean running = latestRun != null && List.of("QUEUED", "RUNNING").contains(latestRun.get("status", String.class));

        EtakAdminLayerManifestService.Layer manifestLayer = manifest.contains(sourceKey) ? manifest.require(sourceKey) : null;
        String purpose = manifestLayer == null
            ? sourceKey.equals(GeospatialRefreshCoordinator.CADASTRE_SOURCE)
                ? "Autoriteetne katastri ruumikuju ja põhiandmed"
                : source.get("source_kind", String.class).equals("TILE")
                    ? "Aluskaartide provider-metaandmete operatiivne kontroll"
                    : null
            : manifestLayer.businessPurpose();
        String caveat = manifestLayer == null ? null : manifestLayer.caveat();

        List<RunStatus> history = recent.stream().map(this::run).toList();
        return new SourceStatus(
            sourceKey,
            source.get("display_name", String.class),
            source.get("provider", String.class),
            source.get("dataset_key", String.class),
            source.get("source_kind", String.class),
            Boolean.TRUE.equals(source.get("enabled", Boolean.class)),
            number(source, "refresh_interval_seconds"),
            freshnessTarget,
            source.get("expected_source_srid", Integer.class),
            source.get("canonical_srid", Integer.class),
            current(current),
            latestRun == null ? null : run(latestRun),
            lastSuccessAt,
            ageSeconds < 0 ? null : ageSeconds,
            freshness,
            running,
            manualRefresh.supports(sourceKey),
            purpose,
            caveat,
            history);
    }

    private CurrentGeneration current(Record record) {
        if (record == null) return null;
        return new CurrentGeneration(
            record.get("id", UUID.class),
            record.get("source_date", java.time.LocalDate.class),
            record.get("source_version", String.class),
            record.get("artifact_checksum", String.class),
            number(record, "artifact_bytes"),
            record.get("schema_fingerprint", String.class),
            number(record, "row_count"),
            time(record, "published_at"));
    }

    private RunStatus run(Record record) {
        OffsetDateTime started = time(record, "started_at");
        OffsetDateTime completed = time(record, "completed_at");
        Long durationMs = started == null || completed == null ? null : Math.max(0, Duration.between(started, completed).toMillis());
        return new RunStatus(
            record.get("id", UUID.class),
            record.get("trigger_type", String.class),
            record.get("status", String.class),
            number(record, "rows_read"),
            number(record, "rows_staged"),
            number(record, "rows_inserted"),
            number(record, "rows_rejected"),
            number(record, "artifact_bytes"),
            durationMs,
            bounded(record.get("error_summary", String.class)),
            started,
            completed,
            time(record, "created_at"));
    }

    private static String freshness(long ageSeconds, Long target, Record latestRun) {
        if (ageSeconds < 0) return "NEVER_SUCCESSFUL";
        boolean latestFailed = latestRun != null && List.of("FAILED", "PARTIAL").contains(latestRun.get("status", String.class));
        if (target != null && ageSeconds > target) return "STALE";
        if (latestFailed) return "DEGRADED";
        return "FRESH";
    }

    private static Long number(Record record, String field) {
        if (record == null) return null;
        Number value = record.get(field, Number.class);
        return value == null ? null : value.longValue();
    }

    private static OffsetDateTime time(Record record, String field) {
        return record == null ? null : record.get(field, OffsetDateTime.class);
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replaceAll("[\\r\\n]+", " ").trim();
        return clean.substring(0, Math.min(600, clean.length()));
    }

    public record SourceStatus(
        String sourceKey,
        String displayName,
        String provider,
        String datasetKey,
        String sourceKind,
        boolean enabled,
        Long cadenceSeconds,
        Long freshnessTargetSeconds,
        Integer expectedSourceSrid,
        Integer canonicalSrid,
        CurrentGeneration currentGeneration,
        RunStatus latestRun,
        OffsetDateTime latestSuccessAt,
        Long latestSuccessAgeSeconds,
        String freshnessState,
        boolean running,
        boolean manualSupported,
        String businessPurpose,
        String caveat,
        List<RunStatus> recentRuns
    ) { }

    public record CurrentGeneration(
        UUID generationId,
        java.time.LocalDate sourceDate,
        String sourceVersion,
        String checksum,
        Long bytes,
        String schemaFingerprint,
        Long rowCount,
        OffsetDateTime publishedAt
    ) { }

    public record RunStatus(
        UUID runId,
        String triggerType,
        String status,
        Long rowsRead,
        Long rowsStaged,
        Long rowsInserted,
        Long rowsRejected,
        Long bytes,
        Long durationMs,
        String errorSummary,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt
    ) { }
}