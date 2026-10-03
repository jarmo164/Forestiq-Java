package ee.forestiq.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import ee.forestiq.geospatial.GeospatialSourceGenerationRepository;
import ee.forestiq.geospatial.GeospatialSourceGenerationRepository.RunCounters;

/**
 * Builds the application-owned canonical forestry generation from the provider/raw
 * Metsaregister mirror.  A publication freezes all manifest-approved local-domain
 * layers into one generation so notifications, compartments, child rows and future
 * vector tiles always resolve through the same source-generation set.
 */
@Service
public class MetsaregisterForestryProjectionService {
    public static final String SOURCE_KEY = "METSAREGISTER_FORESTRY";
    private static final String CANONICAL_SCHEMA_VERSION = "forestry-domain-v1";
    private static final Set<String> TRIGGERS = Set.of("SCHEDULED", "MANUAL", "RECOVERY", "BOOTSTRAP");

    private final DSLContext dsl;
    private final MetsaregisterLayerManifestService manifest;
    private final GeospatialSourceGenerationRepository generations;
    private final ObjectMapper objectMapper;

    public MetsaregisterForestryProjectionService(
        DSLContext dsl,
        MetsaregisterLayerManifestService manifest,
        GeospatialSourceGenerationRepository generations,
        ObjectMapper objectMapper
    ) {
        this.dsl = dsl;
        this.manifest = manifest;
        this.generations = generations;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes the exact current raw-layer set when it differs from the canonical
     * publication. Existing accepted generations are reused instead of duplicated.
     */
    public ProjectionResult refresh(String triggerType, String actorSubject) {
        ProjectionStatus before = status();
        if (!before.ready()) {
            throw new IllegalStateException(
                "Canonical forestry projection is not ready; missing/drifted raw layers: "
                    + String.join(", ", before.missingOrInvalidLayers()));
        }

        List<LayerSnapshot> snapshots = loadSnapshots(true);
        String checksum = compositeChecksum(snapshots);
        UUID currentId = generations.currentGenerationId(SOURCE_KEY);
        String currentChecksum = generationChecksum(currentId);
        if (currentId != null && checksum.equals(currentChecksum)) {
            return new ProjectionResult(currentId, false, false, "UNCHANGED", status());
        }

        var accepted = dsl.fetchOne("""
            SELECT id, status
              FROM geospatial_generation
             WHERE source_key = ?
               AND artifact_checksum = ?
               AND status IN ('VALIDATED', 'PUBLISHED', 'RETIRED')
             ORDER BY created_at DESC
             LIMIT 1
            """, SOURCE_KEY, checksum);
        if (accepted != null) {
            UUID generationId = accepted.get("id", UUID.class);
            String generationStatus = accepted.get("status", String.class);
            if ("RETIRED".equals(generationStatus)) {
                generations.rollbackToRetired(SOURCE_KEY, generationId, actorSubject,
                    "Raw Metsaregister layer set matches an existing known-good canonical generation");
                return new ProjectionResult(generationId, true, true, "REUSED_RETIRED", status());
            }
            if ("VALIDATED".equals(generationStatus)) {
                generations.publishValidated(SOURCE_KEY, generationId, actorSubject,
                    "Resume publication of previously validated canonical forestry generation");
                return new ProjectionResult(generationId, true, true, "RESUMED_VALIDATED", status());
            }
            if ("PUBLISHED".equals(generationStatus)) {
                throw new IllegalStateException(
                    "Published forestry generation exists for the selected source set but is not current: "
                        + generationId);
            }
        }

        String trigger = TRIGGERS.contains(triggerType) ? triggerType : "MANUAL";
        UUID runId = generations.startRun(SOURCE_KEY, trigger);
        UUID generationId = null;
        long expectedRows = snapshots.stream().mapToLong(LayerSnapshot::rowCount).sum();
        try {
            String sourceVersion = "manifest=" + manifest.version() + ";set=" + checksum.substring(0, 16);
            String schemaFingerprint = sha256(CANONICAL_SCHEMA_VERSION + "|" + manifest.version());
            generationId = generations.createStagingGeneration(
                runId, SOURCE_KEY, null, sourceVersion, checksum, null,
                schemaFingerprint, 4326, 3301);

            long insertedRows = populateGeneration(generationId, snapshots);
            if (insertedRows != expectedRows) {
                throw new IllegalStateException(
                    "Canonical forestry row count mismatch: expected=" + expectedRows + ", inserted=" + insertedRows);
            }
            verifyPerLayerCounts(generationId, snapshots);

            dsl.execute("""
                UPDATE geospatial_source_run
                   SET rows_read = ?, rows_staged = ?, rows_inserted = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """, expectedRows, insertedRows, insertedRows, runId);

            generations.markValidated(
                generationId, insertedRows, 0, 0, validationSummary(snapshots, checksum, insertedRows));
            generations.publishValidated(
                SOURCE_KEY, generationId, actorSubject,
                "Published canonical forestry projection from one complete manifest-approved raw generation set");
            return new ProjectionResult(generationId, true, false, "PUBLISHED", status());
        } catch (RuntimeException exception) {
            try {
                generations.finishUnpublishedRun(
                    runId,
                    "FAILED",
                    new RunCounters(expectedRows, 0, 0, 0, 0, 0, 0, 0),
                    safeError(exception));
            } catch (RuntimeException ignored) {
                // Preserve the original projection failure. A later recovery run can diagnose orphan state.
            }
            throw exception;
        }
    }

    /** Returns reconciliation evidence without mutating any source or generation. */
    public ProjectionStatus status() {
        List<LayerSnapshot> snapshots = loadSnapshots(false);
        List<String> missing = new ArrayList<>();
        for (LayerSnapshot snapshot : snapshots) {
            if (!snapshot.valid()) missing.add(snapshot.layerName());
        }
        List<String> expectedLayers = manifest.approvedLocalLayerNames().stream().sorted().toList();
        for (String layer : expectedLayers) {
            if (snapshots.stream().noneMatch(snapshot -> snapshot.layerName().equals(layer))) {
                missing.add(layer);
            }
        }
        missing = missing.stream().distinct().sorted().toList();

        UUID currentId = generations.currentGenerationId(SOURCE_KEY);
        String currentChecksum = generationChecksum(currentId);
        String desiredChecksum = missing.isEmpty() && snapshots.size() == expectedLayers.size()
            ? compositeChecksum(snapshots)
            : null;

        Map<String, UUID> canonicalSources = new LinkedHashMap<>();
        if (currentId != null) {
            dsl.fetch("""
                SELECT layer_name, mirror_generation_id
                  FROM forestry_generation_source
                 WHERE generation_id = ?
                 ORDER BY layer_name
                """, currentId).forEach(record -> canonicalSources.put(
                    record.get("layer_name", String.class),
                    record.get("mirror_generation_id", UUID.class)));
        }

        List<String> drift = new ArrayList<>();
        for (LayerSnapshot snapshot : snapshots) {
            UUID canonicalGeneration = canonicalSources.get(snapshot.layerName());
            if (currentId != null && snapshot.currentGenerationId() != null
                && !snapshot.currentGenerationId().equals(canonicalGeneration)) {
                drift.add(snapshot.layerName());
            }
        }
        if (currentId != null && canonicalSources.size() != expectedLayers.size()) {
            for (String layer : expectedLayers) {
                if (!canonicalSources.containsKey(layer)) drift.add(layer);
            }
        }
        drift = drift.stream().distinct().sorted().toList();

        boolean ready = missing.isEmpty() && snapshots.size() == expectedLayers.size();
        boolean inSync = ready && currentId != null && drift.isEmpty()
            && desiredChecksum != null && desiredChecksum.equals(currentChecksum);
        return new ProjectionStatus(
            currentId,
            manifest.version(),
            ready,
            inSync,
            currentChecksum,
            desiredChecksum,
            List.copyOf(missing),
            List.copyOf(drift),
            List.copyOf(snapshots));
    }

    public ProjectionStatus rollback(UUID generationId, String actorSubject, String reason) {
        generations.rollbackToRetired(
            SOURCE_KEY, generationId, actorSubject,
            reason == null || reason.isBlank() ? "Manual canonical forestry rollback" : reason);
        return status();
    }

    private List<LayerSnapshot> loadSnapshots(boolean strict) {
        List<LayerSnapshot> result = new ArrayList<>();
        for (String layerName : manifest.approvedLocalLayerNames().stream().sorted().toList()) {
            var record = dsl.fetchOne("""
                SELECT l.current_generation_id,
                       l.current_row_count,
                       l.state AS layer_state,
                       g.state AS generation_state,
                       g.rows_staged,
                       (SELECT count(*)::bigint
                          FROM metsaregister_wfs_feature f
                         WHERE f.layer_name = l.layer_name
                           AND f.generation_id = l.current_generation_id) AS actual_rows
                  FROM metsaregister_wfs_layer l
                  LEFT JOIN metsaregister_wfs_generation g
                    ON g.id = l.current_generation_id
                   AND g.layer_name = l.layer_name
                 WHERE l.layer_name = ?
                   AND l.approved = TRUE
                   AND l.enabled = TRUE
                   AND l.consumption_mode = 'LOCAL_DOMAIN_DATA'
                """, layerName);
            if (record == null) {
                if (strict) throw new IllegalStateException("Missing approved raw layer policy: " + layerName);
                result.add(new LayerSnapshot(layerName, null, 0, 0, null, null, false));
                continue;
            }
            UUID generationId = record.get("current_generation_id", UUID.class);
            Long expected = record.get("rows_staged", Long.class);
            Long actual = record.get("actual_rows", Long.class);
            String generationState = record.get("generation_state", String.class);
            String layerState = record.get("layer_state", String.class);
            long expectedRows = expected == null ? 0L : expected;
            long actualRows = actual == null ? 0L : actual;
            boolean valid = generationId != null
                && "PUBLISHED".equals(generationState)
                && expectedRows == actualRows;
            if (strict && !valid) {
                throw new IllegalStateException(
                    "Raw Metsaregister layer is not a complete published generation: " + layerName
                        + " generation=" + generationId
                        + " state=" + generationState
                        + " expectedRows=" + expectedRows
                        + " actualRows=" + actualRows);
            }
            result.add(new LayerSnapshot(
                layerName, generationId, expectedRows, actualRows, layerState, generationState, valid));
        }
        result.sort(Comparator.comparing(LayerSnapshot::layerName));
        return List.copyOf(result);
    }

    private long populateGeneration(UUID generationId, List<LayerSnapshot> snapshots) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            for (LayerSnapshot snapshot : snapshots) {
                tx.execute("""
                    INSERT INTO forestry_generation_source
                        (generation_id, source_key, layer_name, mirror_generation_id,
                         source_row_count, manifest_version)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, generationId, SOURCE_KEY, snapshot.layerName(), snapshot.currentGenerationId(),
                    snapshot.rowCount(), manifest.version());
            }

            int inserted = tx.execute("""
                INSERT INTO forestry_domain_feature
                    (source_key, generation_id, layer_name, mirror_generation_id,
                     source_feature_key, source_id, source_hash,
                     cadastral_code, compartment_id, domain_code, title,
                     work_code, decision, provider_status, notification_number,
                     cadastre_subpart_code, damage_code,
                     registration_date, confirmation_date, archive_date, event_date,
                     archived, area, amount_to_be_cut, volume, attributes, geom)
                SELECT
                    ?, s.generation_id, f.layer_name, f.generation_id,
                    f.feature_key, f.source_id, f.source_hash,
                    COALESCE(
                        NULLIF(f.properties ->> 'katastri_nr', ''),
                        NULLIF(f.properties ->> 'katastritunnus', ''),
                        NULLIF(f.properties ->> 'katastritunnus_', ''),
                        NULLIF(f.properties ->> 'tunnus', '')
                    ),
                    CASE
                        WHEN f.layer_name = 'metsaregister:eraldis' THEN COALESCE(
                            NULLIF(f.properties ->> 'sys_id', ''), f.source_id, f.feature_key)
                        WHEN f.layer_name IN (
                            'metsaregister:eraldis_element',
                            'metsaregister:isearasused',
                            'metsaregister:kahjustused'
                        ) THEN COALESCE(
                            NULLIF(f.properties ->> 'eraldis_id', ''),
                            NULLIF(f.properties ->> 'eraldis_sys_id', ''),
                            NULLIF(f.properties ->> 'parent_id', '')
                        )
                        ELSE NULL
                    END,
                    COALESCE(NULLIF(f.properties ->> 'kood', ''), NULLIF(f.properties ->> 'code', '')),
                    COALESCE(
                        NULLIF(f.properties ->> 'teatise_nr', ''),
                        NULLIF(f.properties ->> 'eraldise_nr', ''),
                        NULLIF(f.properties ->> 'kinnistu_nimetus', ''),
                        NULLIF(f.properties ->> 'kinnistu_nr', ''),
                        NULLIF(f.properties ->> 'nimetus', ''),
                        NULLIF(f.properties ->> 'nimi', ''),
                        NULLIF(f.properties ->> 'kirjeldus', '')
                    ),
                    COALESCE(NULLIF(f.properties ->> 'too_kood', ''), NULLIF(f.properties ->> 'work_code', '')),
                    COALESCE(NULLIF(f.properties ->> 'otsus', ''), NULLIF(f.properties ->> 'decision', '')),
                    COALESCE(
                        NULLIF(f.properties ->> 'staatus', ''),
                        NULLIF(f.properties ->> 'status', ''),
                        NULLIF(f.properties ->> 'state', ''),
                        NULLIF(f.properties ->> 'kahjustuse_aste', ''),
                        NULLIF(f.properties ->> 'aste', '')
                    ),
                    COALESCE(
                        NULLIF(f.properties ->> 'teatise_nr', ''),
                        NULLIF(f.properties ->> 'teatis_nr', ''),
                        NULLIF(f.properties ->> 'number', ''),
                        f.source_id,
                        f.feature_key
                    ),
                    COALESCE(
                        metsaregister_safe_bigint(f.properties ->> 'eraldise_nr'),
                        metsaregister_safe_bigint(f.properties ->> 'eraldise_id'),
                        metsaregister_safe_bigint(f.properties ->> 'eraldis_nr')
                    ),
                    COALESCE(
                        NULLIF(f.properties ->> 'kahjustuse_kood', ''),
                        NULLIF(f.properties ->> 'kahjustus_kood', ''),
                        CASE WHEN f.layer_name = 'metsaregister:kahjustused'
                             THEN NULLIF(f.properties ->> 'kood', '') ELSE NULL END
                    ),
                    COALESCE(
                        metsaregister_safe_bigint(f.properties ->> 'registreerimise_kp'),
                        metsaregister_safe_bigint(f.properties ->> 'registreerimise_kuupaev'),
                        metsaregister_safe_bigint(f.properties ->> 'registration_date')
                    ),
                    COALESCE(
                        metsaregister_safe_bigint(f.properties ->> 'kinnitamise_kp'),
                        metsaregister_safe_bigint(f.properties ->> 'confirmation_date')
                    ),
                    COALESCE(
                        metsaregister_safe_bigint(f.properties ->> 'arhiivi_kp'),
                        metsaregister_safe_bigint(f.properties ->> 'archive_date')
                    ),
                    COALESCE(
                        metsaregister_safe_bigint(f.properties ->> 'sundmuse_kp'),
                        metsaregister_safe_bigint(f.properties ->> 'event_date'),
                        metsaregister_safe_bigint(f.properties ->> 'registreerimise_kp')
                    ),
                    (f.layer_name = 'metsaregister:teatis_arhiiv'),
                    metsaregister_safe_numeric(f.properties ->> 'pindala'),
                    COALESCE(
                        metsaregister_safe_numeric(f.properties ->> 'raiutav_maht'),
                        metsaregister_safe_numeric(f.properties ->> 'amount_to_be_cut')
                    ),
                    COALESCE(
                        metsaregister_safe_numeric(f.properties ->> 'raiutav_maht'),
                        metsaregister_safe_numeric(f.properties ->> 'tagavara_y_ha'),
                        metsaregister_safe_numeric(f.properties ->> 'maht')
                    ),
                    f.properties,
                    CASE
                        WHEN f.geometry IS NULL OR ST_IsEmpty(f.geometry) THEN NULL
                        ELSE ST_Transform(ST_MakeValid(ST_Force2D(f.geometry)), 3301)
                    END
                  FROM forestry_generation_source s
                  JOIN metsaregister_wfs_feature f
                    ON f.layer_name = s.layer_name
                   AND f.generation_id = s.mirror_generation_id
                 WHERE s.generation_id = ?
                """, SOURCE_KEY, generationId);
            return (long) inserted;
        });
    }

    private void verifyPerLayerCounts(UUID generationId, List<LayerSnapshot> snapshots) {
        Map<String, Long> actual = new LinkedHashMap<>();
        dsl.fetch("""
            SELECT layer_name, count(*)::bigint AS row_count
              FROM forestry_domain_feature
             WHERE generation_id = ?
             GROUP BY layer_name
            """, generationId).forEach(record -> actual.put(
                record.get("layer_name", String.class),
                record.get("row_count", Long.class)));
        for (LayerSnapshot snapshot : snapshots) {
            long canonicalRows = actual.getOrDefault(snapshot.layerName(), 0L);
            if (canonicalRows != snapshot.rowCount()) {
                throw new IllegalStateException(
                    "Canonical forestry layer count mismatch for " + snapshot.layerName()
                        + ": raw=" + snapshot.rowCount() + ", canonical=" + canonicalRows);
            }
        }
    }

    private String validationSummary(List<LayerSnapshot> snapshots, String checksum, long rowCount) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "canonicalSchema", CANONICAL_SCHEMA_VERSION,
                "manifestVersion", manifest.version(),
                "compositeChecksum", checksum,
                "rowCount", rowCount,
                "layers", snapshots.stream().map(snapshot -> Map.of(
                    "layer", snapshot.layerName(),
                    "mirrorGenerationId", snapshot.currentGenerationId().toString(),
                    "rows", snapshot.rowCount()
                )).toList()
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encode canonical forestry validation summary", exception);
        }
    }

    private String generationChecksum(UUID generationId) {
        if (generationId == null) return null;
        var record = dsl.fetchOne("""
            SELECT artifact_checksum
              FROM geospatial_generation
             WHERE source_key = ? AND id = ?
            """, SOURCE_KEY, generationId);
        return record == null ? null : record.get("artifact_checksum", String.class);
    }

    private String compositeChecksum(List<LayerSnapshot> snapshots) {
        StringBuilder value = new StringBuilder(CANONICAL_SCHEMA_VERSION)
            .append('|').append(manifest.version());
        snapshots.stream().sorted(Comparator.comparing(LayerSnapshot::layerName)).forEach(snapshot -> value
            .append('|').append(snapshot.layerName())
            .append('=').append(snapshot.currentGenerationId())
            .append(':').append(snapshot.rowCount()));
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String clean = message.replaceAll("[\\r\\n]+", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 1_000));
    }

    public record LayerSnapshot(
        String layerName,
        UUID currentGenerationId,
        long rowCount,
        long actualRows,
        String layerState,
        String generationState,
        boolean valid
    ) { }

    public record ProjectionStatus(
        UUID currentGenerationId,
        String manifestVersion,
        boolean ready,
        boolean inSync,
        String currentChecksum,
        String desiredChecksum,
        List<String> missingOrInvalidLayers,
        List<String> driftedLayers,
        List<LayerSnapshot> rawLayers
    ) { }

    public record ProjectionResult(
        UUID generationId,
        boolean changed,
        boolean reusedKnownGood,
        String outcome,
        ProjectionStatus status
    ) {
        public Map<String, Object> metrics() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("generationId", generationId == null ? null : generationId.toString());
            values.put("changed", changed);
            values.put("reusedKnownGood", reusedKnownGood);
            values.put("outcome", outcome);
            values.put("ready", status.ready());
            values.put("inSync", status.inSync());
            values.put("manifestVersion", status.manifestVersion());
            values.put("driftedLayers", status.driftedLayers());
            return Map.copyOf(values);
        }
    }
}
