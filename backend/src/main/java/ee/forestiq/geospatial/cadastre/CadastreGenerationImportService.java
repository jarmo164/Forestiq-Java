package ee.forestiq.geospatial.cadastre;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generation-safe nationwide cadastral import.
 *
 * <p>Raw GDAL staging is never reader-visible. Canonical rows are written under an immutable
 * generation id and become visible only after validation and an atomic current-pointer cutover.</p>
 */
@Service
public class CadastreGenerationImportService {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final String SOURCE_KEY = CadastreSnapshotCheckService.SOURCE_KEY;
    private static final String EXPORT_DATE_FIELD = "eksport";

    private static final double ESTONIA_MIN_X = 250_000d;
    private static final double ESTONIA_MIN_Y = 6_300_000d;
    private static final double ESTONIA_MAX_X = 850_000d;
    private static final double ESTONIA_MAX_Y = 6_700_000d;

    private final CadastreSnapshotArchive archive;
    private final CadastreGdalAdapter gdal;
    private final GeospatialSourceGenerationRepository generations;
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final long minimumRows;
    private final double maximumRowCountDriftRatio;

    public CadastreGenerationImportService(
        CadastreSnapshotArchive archive,
        CadastreGdalAdapter gdal,
        GeospatialSourceGenerationRepository generations,
        DSLContext dsl,
        ObjectMapper objectMapper,
        @Value("${forestiq.geospatial.cadastre.minimum-rows:${MARU_CADASTRE_MINIMUM_ROWS:500000}}") long minimumRows,
        @Value("${forestiq.geospatial.cadastre.maximum-row-count-drift-ratio:${MARU_CADASTRE_MAX_ROW_COUNT_DRIFT_RATIO:0.15}}") double maximumRowCountDriftRatio
    ) {
        if (minimumRows <= 0) {
            throw new IllegalArgumentException("minimum cadastral row count must be positive");
        }
        if (!(maximumRowCountDriftRatio > 0d && maximumRowCountDriftRatio < 1d)) {
            throw new IllegalArgumentException("maximum row count drift ratio must be between 0 and 1");
        }
        this.archive = archive;
        this.gdal = gdal;
        this.generations = generations;
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.minimumRows = minimumRows;
        this.maximumRowCountDriftRatio = maximumRowCountDriftRatio;
    }

    public ImportResult importAndPublish(CadastreSnapshotCheckService.SnapshotCheckResult prepared,
                                         String actorSubject)
        throws IOException, InterruptedException {
        if (prepared == null) {
            throw new IllegalArgumentException("Prepared snapshot check is required");
        }
        if (!prepared.changed()) {
            return ImportResult.unchanged(prepared.runId(), prepared.snapshot().sha256());
        }

        UUID runId = prepared.runId();
        String stageTable = null;
        long rowsRead = 0L;
        long rowsStaged = 0L;

        try (CadastreSnapshotArchive.ExtractedGeoPackage extracted = archive.extract(prepared.snapshot())) {
            CadastreGdalAdapter.GeoPackageManifest manifest = gdal.inspect(extracted.path());
            stageTable = gdal.importToStage(extracted.path(), manifest, runId);

            StageValidation validation = validateStage(stageTable, manifest);
            rowsRead = validation.totalRows();
            if (!validation.publishable()) {
                throw new IOException("Cadastral staging validation failed: " + validation.failureSummary());
            }

            UUID previousGeneration = generations.currentGenerationId(SOURCE_KEY);
            long previousRows = previousGeneration == null || fixtureGeneration(previousGeneration)
                ? 0L
                : generationRowCount(previousGeneration);
            validateRowCountDrift(validation.totalRows(), previousRows);

            UUID generationId = generations.createStagingGeneration(
                runId,
                SOURCE_KEY,
                validation.sourceDate(),
                prepared.snapshot().observedSourceVersion(),
                prepared.snapshot().sha256(),
                prepared.snapshot().bytes(),
                manifest.schemaFingerprint(),
                manifest.sourceSrid(),
                3301);

            insertCanonicalGeneration(stageTable, manifest.codeField(), generationId);
            rowsStaged = validation.totalRows();

            GenerationCounters counters = compareToPrevious(generationId, previousGeneration);
            recordRunCounters(runId, validation.totalRows(), validation.repairedGeometries(), counters);
            dsl.execute("ANALYZE geospatial_cadastre_parcel");

            String validationSummary = validationSummary(manifest, validation, previousRows, counters);
            generations.markValidated(
                generationId,
                validation.totalRows(),
                0,
                validation.repairedGeometries(),
                validationSummary);
            generations.publishValidated(
                SOURCE_KEY,
                generationId,
                actorSubject,
                "Verified nationwide Maa- ja Ruumiamet GeoPackage publication");

            return ImportResult.published(
                runId,
                generationId,
                prepared.snapshot().sha256(),
                validation.totalRows(),
                counters);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                generations.finishUnpublishedRun(
                    runId,
                    "FAILED",
                    new GeospatialSourceGenerationRepository.RunCounters(
                        rowsRead, rowsStaged, 0, 0, 0, 0, 0, 0),
                    safeMessage(ex));
            } catch (RuntimeException completionFailure) {
                ex.addSuppressed(completionFailure);
            }
            throw ex;
        } finally {
            if (stageTable != null) {
                dropStageQuietly(stageTable);
            }
        }
    }

    private StageValidation validateStage(String stageTable, CadastreGdalAdapter.GeoPackageManifest manifest) {
        String table = quoted(stageTable);
        String code = quoted(manifest.codeField());
        String exportDate = quoted(EXPORT_DATE_FIELD);

        Record record = dsl.fetchOne("""
            SELECT count(*)::bigint AS total_rows,
                   count(*) FILTER (
                       WHERE NULLIF(btrim(%s::text), '') IS NULL
                   )::bigint AS missing_codes,
                   (
                       count(*) FILTER (WHERE NULLIF(btrim(%s::text), '') IS NOT NULL)
                       - count(DISTINCT NULLIF(btrim(%s::text), ''))
                   )::bigint AS duplicate_codes,
                   count(*) FILTER (
                       WHERE geom IS NULL
                          OR ST_IsEmpty(geom)
                          OR GeometryType(geom) NOT IN ('POLYGON', 'MULTIPOLYGON')
                          OR (
                              NOT ST_IsValid(geom)
                              AND (
                                  ST_IsEmpty(ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3))
                                  OR NOT ST_IsValid(ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3))
                              )
                          )
                   )::bigint AS invalid_geometries,
                   count(*) FILTER (
                       WHERE geom IS NOT NULL
                         AND NOT ST_IsEmpty(geom)
                         AND GeometryType(geom) IN ('POLYGON', 'MULTIPOLYGON')
                         AND NOT ST_IsValid(geom)
                         AND NOT ST_IsEmpty(ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3))
                         AND ST_IsValid(ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3))
                   )::bigint AS repaired_geometries,
                   count(*) FILTER (
                       WHERE geom IS NOT NULL AND ST_SRID(geom) <> 3301
                   )::bigint AS wrong_srid,
                   count(*) FILTER (
                       WHERE geom IS NOT NULL
                         AND ST_SRID(geom) = 3301
                         AND NOT ST_CoveredBy(
                             ST_Envelope(geom),
                             ST_MakeEnvelope(?, ?, ?, ?, 3301)
                         )
                   )::bigint AS outside_estonia_guard,
                   min(%s::date) AS export_min,
                   max(%s::date) AS export_max
              FROM %s
            """.formatted(code, code, code, exportDate, exportDate, table),
            ESTONIA_MIN_X, ESTONIA_MIN_Y, ESTONIA_MAX_X, ESTONIA_MAX_Y);

        if (record == null) {
            throw new IllegalStateException("Cadastral staging validation returned no result");
        }

        long total = number(record, "total_rows");
        long missing = number(record, "missing_codes");
        long duplicates = number(record, "duplicate_codes");
        long invalid = number(record, "invalid_geometries");
        long repaired = number(record, "repaired_geometries");
        long wrongSrid = number(record, "wrong_srid");
        long outside = number(record, "outside_estonia_guard");
        LocalDate exportMin = record.get("export_min", LocalDate.class);
        LocalDate exportMax = record.get("export_max", LocalDate.class);

        boolean providerCountMatches = manifest.providerFeatureCount() < 0
            || manifest.providerFeatureCount() == total;
        boolean oneSourceDate = exportMin != null && exportMin.equals(exportMax);
        boolean publishable = total >= minimumRows
            && missing == 0
            && duplicates == 0
            && invalid == 0
            && wrongSrid == 0
            && outside == 0
            && providerCountMatches
            && oneSourceDate;

        return new StageValidation(
            total,
            missing,
            duplicates,
            invalid,
            repaired,
            wrongSrid,
            outside,
            manifest.providerFeatureCount(),
            exportMin,
            exportMax,
            publishable);
    }

    private void validateRowCountDrift(long newRows, long previousRows) throws IOException {
        if (previousRows <= 0) {
            return;
        }
        double drift = Math.abs(newRows - previousRows) / (double) previousRows;
        if (drift > maximumRowCountDriftRatio) {
            throw new IOException(
                "Cadastral row-count drift "
                    + String.format(java.util.Locale.ROOT, "%.4f", drift)
                    + " exceeds allowed " + maximumRowCountDriftRatio
                    + " (previous=" + previousRows + ", new=" + newRows + ")");
        }
    }

    private void insertCanonicalGeneration(String stageTable, String codeField, UUID generationId) {
        String table = quoted(stageTable);
        String code = quoted(codeField);

        int inserted = dsl.execute("""
            INSERT INTO geospatial_cadastre_parcel
                (source_key, generation_id, cadastral_code, provider_feature_id,
                 source_attributes, source_area_m2, geom)
            SELECT 'MARU_CADASTRE',
                   ?,
                   btrim(%s::text),
                   NULL,
                   to_jsonb(s) - 'geom',
                   ST_Area(canonical_geom),
                   ST_Multi(canonical_geom)::geometry(MultiPolygon, 3301)
              FROM (
                   SELECT s.*,
                          ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3) AS canonical_geom
                     FROM %s s
              ) s
            """.formatted(code, table), generationId);

        Record canonicalCount = dsl.fetchOne(
            "SELECT count(*)::bigint FROM geospatial_cadastre_parcel WHERE generation_id = ?",
            generationId);
        Long canonicalRows = canonicalCount == null ? null : canonicalCount.get(0, Long.class);
        if (canonicalRows == null || canonicalRows != inserted) {
            throw new IllegalStateException(
                "Canonical cadastral generation row count does not match inserted rows");
        }
    }

    private GenerationCounters compareToPrevious(UUID generationId, UUID previousGeneration) {
        if (previousGeneration == null) {
            return new GenerationCounters(generationRowCount(generationId), 0, 0, 0);
        }

        Record record = dsl.fetchOne("""
            WITH cur AS (
                SELECT cadastral_code,
                       source_attributes - 'eksport' - 'ogr_fid' AS comparable_attributes,
                       geom
                  FROM geospatial_cadastre_parcel
                 WHERE generation_id = ?
            ), old AS (
                SELECT cadastral_code,
                       source_attributes - 'eksport' - 'ogr_fid' AS comparable_attributes,
                       geom
                  FROM geospatial_cadastre_parcel
                 WHERE generation_id = ?
            )
            SELECT count(*) FILTER (WHERE old.cadastral_code IS NULL)::bigint AS inserted_rows,
                   count(*) FILTER (WHERE cur.cadastral_code IS NULL)::bigint AS removed_rows,
                   count(*) FILTER (
                       WHERE cur.cadastral_code IS NOT NULL
                         AND old.cadastral_code IS NOT NULL
                         AND (
                           cur.comparable_attributes IS DISTINCT FROM old.comparable_attributes
                           OR NOT ST_Equals(cur.geom, old.geom)
                         )
                   )::bigint AS updated_rows,
                   count(*) FILTER (
                       WHERE cur.cadastral_code IS NOT NULL
                         AND old.cadastral_code IS NOT NULL
                         AND cur.comparable_attributes IS NOT DISTINCT FROM old.comparable_attributes
                         AND ST_Equals(cur.geom, old.geom)
                   )::bigint AS unchanged_rows
              FROM cur
              FULL OUTER JOIN old USING (cadastral_code)
            """, generationId, previousGeneration);

        if (record == null) {
            throw new IllegalStateException("Generation comparison returned no result");
        }
        return new GenerationCounters(
            number(record, "inserted_rows"),
            number(record, "updated_rows"),
            number(record, "unchanged_rows"),
            number(record, "removed_rows"));
    }

    private void recordRunCounters(UUID runId, long rowsRead, long repairedGeometries, GenerationCounters counters) {
        int updated = dsl.execute("""
            UPDATE geospatial_source_run
               SET rows_read = ?,
                   rows_staged = ?,
                   rows_inserted = ?,
                   rows_updated = ?,
                   rows_unchanged = ?,
                   rows_removed = ?,
                   rows_rejected = 0,
                   geometries_repaired = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ?
               AND status = 'RUNNING'
            """,
            rowsRead,
            rowsRead,
            counters.inserted(),
            counters.updated(),
            counters.unchanged(),
            counters.removed(),
            repairedGeometries,
            runId);
        if (updated != 1) {
            throw new IllegalStateException("Active geospatial run disappeared during cadastral import");
        }
    }

    private long generationRowCount(UUID generationId) {
        Record countRecord = dsl.fetchOne(
            "SELECT count(*)::bigint FROM geospatial_cadastre_parcel WHERE generation_id = ?",
            generationId);
        Long count = countRecord == null ? null : countRecord.get(0, Long.class);
        return count == null ? 0L : count;
    }

    private boolean fixtureGeneration(UUID generationId) {
        Record record = dsl.fetchOne(
            "SELECT COALESCE((validation_summary->>'fixture')::boolean, false) "
                + "FROM geospatial_generation WHERE id = ?",
            generationId);
        Boolean fixture = record == null ? null : record.get(0, Boolean.class);
        return Boolean.TRUE.equals(fixture);
    }

    private String validationSummary(CadastreGdalAdapter.GeoPackageManifest manifest,
                                     StageValidation validation,
                                     long previousRows,
                                     GenerationCounters counters) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("layer", manifest.layerName());
        root.put("codeField", manifest.codeField());
        root.put("sourceSrid", manifest.sourceSrid());
        root.put("canonicalSrid", 3301);
        root.put("schemaFingerprint", manifest.schemaFingerprint());
        root.put("rows", validation.totalRows());
        root.put("providerFeatureCount", validation.providerFeatureCount());
        root.put("previousRows", previousRows);
        root.put("missingCodes", validation.missingCodes());
        root.put("duplicateCodes", validation.duplicateCodes());
        root.put("invalidGeometries", validation.invalidGeometries());
        root.put("repairedGeometries", validation.repairedGeometries());
        root.put("wrongSrid", validation.wrongSrid());
        root.put("outsideEstoniaGuard", validation.outsideEstoniaGuard());
        root.put("estoniaGuard", "L-EST97_COARSE_ENVELOPE_V1");
        if (validation.sourceDate() != null) {
            root.put("sourceDate", validation.sourceDate().toString());
        } else {
            root.putNull("sourceDate");
        }
        root.put("inserted", counters.inserted());
        root.put("updated", counters.updated());
        root.put("unchanged", counters.unchanged());
        root.put("removed", counters.removed());
        return root.toString();
    }

    private void dropStageQuietly(String stageTable) {
        try {
            dsl.execute("DROP TABLE IF EXISTS " + quoted(stageTable));
        } catch (RuntimeException ignored) {
            // Staging is never reader-visible. A cleanup failure is operationally recoverable.
        }
    }

    private static long number(Record record, String field) {
        Number value = record.get(field, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private static String quoted(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return '"' + identifier + '"';
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String clean = message.replaceAll("[\\r\\n]+", " ").trim();
        return ex.getClass().getSimpleName() + ": "
            + clean.substring(0, Math.min(1500, clean.length()));
    }

    public record StageValidation(
        long totalRows,
        long missingCodes,
        long duplicateCodes,
        long invalidGeometries,
        long repairedGeometries,
        long wrongSrid,
        long outsideEstoniaGuard,
        long providerFeatureCount,
        LocalDate exportMin,
        LocalDate exportMax,
        boolean publishable
    ) {
        LocalDate sourceDate() {
            return exportMin != null && exportMin.equals(exportMax) ? exportMin : null;
        }

        String failureSummary() {
            return "rows=" + totalRows
                + ", missingCodes=" + missingCodes
                + ", duplicateCodes=" + duplicateCodes
                + ", invalidGeometries=" + invalidGeometries
                + ", repairedGeometries=" + repairedGeometries
                + ", wrongSrid=" + wrongSrid
                + ", outsideEstoniaGuard=" + outsideEstoniaGuard
                + ", providerFeatureCount=" + providerFeatureCount
                + ", exportMin=" + exportMin
                + ", exportMax=" + exportMax;
        }
    }

    public record GenerationCounters(long inserted, long updated, long unchanged, long removed) {
    }

    public record ImportResult(
        UUID runId,
        UUID generationId,
        boolean published,
        boolean unchanged,
        String artifactChecksum,
        long rows,
        GenerationCounters counters
    ) {
        static ImportResult unchanged(UUID runId, String checksum) {
            return new ImportResult(
                runId,
                null,
                false,
                true,
                checksum,
                0,
                new GenerationCounters(0, 0, 0, 0));
        }

        static ImportResult published(UUID runId,
                                      UUID generationId,
                                      String checksum,
                                      long rows,
                                      GenerationCounters counters) {
            return new ImportResult(runId, generationId, true, false, checksum, rows, counters);
        }
    }
}
