package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the published Metsaregister mirror independently from tenant
 * projections. The normal check is metadata-only and cheap; deep mode counts
 * the actual current-generation feature rows and is intended for controlled
 * operational verification rather than frequent polling.
 */
@Service
class MetsaregisterWfsVerificationService {
    private final DSLContext dsl;

    MetsaregisterWfsVerificationService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    VerificationReport verify(boolean deep) {
        Instant verifiedAt = Instant.now();
        Map<String, Long> actualCounts = deep ? actualCurrentFeatureCounts() : Map.of();
        List<LayerCheck> layers = dsl.fetch("""
            SELECT l.layer_name, l.title, l.state, l.current_generation_id,
                   l.current_row_count, l.provider_number_matched,
                   l.last_success_at, l.last_failure_at,
                   g.rows_staged AS generation_rows,
                   g.provider_number_matched AS generation_provider_number_matched
              FROM metsaregister_wfs_layer l
              LEFT JOIN metsaregister_wfs_generation g
                ON g.id=l.current_generation_id AND g.layer_name=l.layer_name
             WHERE l.approved=TRUE AND l.enabled=TRUE AND l.provider_present=TRUE
             ORDER BY l.layer_name
            """).map(record -> check(record, actualCounts.get(record.get("layer_name", String.class)), deep));

        layers.forEach(layer -> recordVerification(layer, verifiedAt, deep));

        long complete = layers.stream().filter(layer -> "COMPLETE".equals(layer.completeness())).count();
        long mismatch = layers.stream().filter(layer -> layer.completeness().contains("MISMATCH")).count();
        long unknown = layers.stream().filter(layer -> "UNKNOWN_PROVIDER_COUNT".equals(layer.completeness())).count();
        long noGeneration = layers.stream().filter(layer -> "NO_GENERATION".equals(layer.completeness())).count();
        long largeLayers = layers.stream().filter(LayerCheck::overOneMillionRows).count();

        return new VerificationReport(
            verifiedAt, deep, layers.size(), complete, mismatch, unknown, noGeneration, largeLayers,
            indexExists("metsaregister_wfs_feature_geometry_gix"),
            indexExists("metsaregister_wfs_feature_layer_generation_idx"),
            functionExists("metsaregister_wfs_features_for_parcel"),
            stateCount("STAGING"), stateCount("RETIRED"), List.copyOf(layers));
    }

    private LayerCheck check(Record record, Long actualRows, boolean deep) {
        String layerName = record.get("layer_name", String.class);
        UUID generation = record.get("current_generation_id", UUID.class);
        Long currentRows = record.get("current_row_count", Long.class);
        Long providerRows = record.get("provider_number_matched", Long.class);
        Long generationRows = record.get("generation_rows", Long.class);
        Long generationProviderRows = record.get("generation_provider_number_matched", Long.class);

        String completeness;
        if (generation == null) {
            completeness = "NO_GENERATION";
        } else if (!same(currentRows, generationRows)) {
            completeness = "GENERATION_METADATA_MISMATCH";
        } else if (providerRows == null && generationProviderRows == null) {
            completeness = "UNKNOWN_PROVIDER_COUNT";
        } else if (!same(providerRows, generationProviderRows)) {
            completeness = "PROVIDER_METADATA_MISMATCH";
        } else if (providerRows != null && !providerRows.equals(currentRows)) {
            completeness = "ROW_COUNT_MISMATCH";
        } else if (deep && actualRows != null && !actualRows.equals(currentRows)) {
            completeness = "ACTUAL_ROW_COUNT_MISMATCH";
        } else {
            completeness = "COMPLETE";
        }

        long sizeReference = Math.max(
            currentRows == null ? 0L : currentRows,
            providerRows == null ? 0L : providerRows);
        return new LayerCheck(
            layerName, record.get("title", String.class), record.get("state", String.class), generation,
            currentRows, providerRows, generationRows, actualRows, completeness,
            sizeReference > 1_000_000L,
            record.get("last_success_at", Instant.class), record.get("last_failure_at", Instant.class));
    }

    private void recordVerification(LayerCheck layer, Instant verifiedAt, boolean deep) {
        Long localCount = deep && layer.actualFeatureRows() != null
            ? layer.actualFeatureRows()
            : layer.currentRowCount();
        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("last_verified_at"), Instant.class), verifiedAt)
            .set(field(name("last_verification_state"), String.class), layer.completeness())
            .set(field(name("last_verified_local_count"), Long.class), localCount)
            .set(field(name("last_verified_provider_count"), Long.class), layer.providerNumberMatched())
            .where(field(name("layer_name"), String.class).eq(layer.layerName()))
            .execute();
    }

    private Map<String, Long> actualCurrentFeatureCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        dsl.fetch("""
            SELECT l.layer_name, count(f.feature_key)::bigint AS actual_rows
              FROM metsaregister_wfs_layer l
              LEFT JOIN metsaregister_wfs_feature f
                ON f.layer_name=l.layer_name AND f.generation_id=l.current_generation_id
             WHERE l.approved=TRUE AND l.enabled=TRUE AND l.provider_present=TRUE
             GROUP BY l.layer_name
             ORDER BY l.layer_name
            """).forEach(record -> counts.put(
                record.get("layer_name", String.class), record.get("actual_rows", Long.class)));
        return counts;
    }

    private boolean indexExists(String indexName) {
        return dsl.fetchExists(
            dsl.selectOne().from(table("pg_indexes"))
                .where(field(name("schemaname"), String.class).eq("public")
                    .and(field(name("indexname"), String.class).eq(indexName)))
        );
    }

    private boolean functionExists(String functionName) {
        Integer count = dsl.fetchOne("""
            SELECT count(*)::int AS c
              FROM pg_proc p
              JOIN pg_namespace n ON n.oid=p.pronamespace
             WHERE n.nspname='public' AND p.proname=?
            """, functionName).get("c", Integer.class);
        return count != null && count > 0;
    }

    private long stateCount(String state) {
        return dsl.selectCount()
            .from(table("metsaregister_wfs_generation"))
            .where(field(name("state"), String.class).eq(state))
            .fetchOne(0, long.class);
    }

    private static boolean same(Long left, Long right) {
        if (left == null || right == null) return left == right;
        return left.equals(right);
    }

    record VerificationReport(
        Instant generatedAt,
        boolean deep,
        int approvedPresentLayers,
        long completeLayers,
        long mismatchLayers,
        long unknownProviderCountLayers,
        long noGenerationLayers,
        long overOneMillionRowLayers,
        boolean geometryGistIndexPresent,
        boolean generationLookupIndexPresent,
        boolean parcelIntersectionFunctionPresent,
        long stagingGenerations,
        long retiredGenerations,
        List<LayerCheck> layers
    ) { }

    record LayerCheck(
        String layerName,
        String title,
        String state,
        UUID currentGenerationId,
        Long currentRowCount,
        Long providerNumberMatched,
        Long generationRowsStaged,
        Long actualFeatureRows,
        String completeness,
        boolean overOneMillionRows,
        Instant lastSuccessAt,
        Instant lastFailureAt
    ) { }
}
