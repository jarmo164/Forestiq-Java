package ee.forestiq.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ee.forestiq.map.MapTileAdmissionGate;
import ee.forestiq.map.MapTileAdmissionGate.Family;

/** PostGIS MVT encoder over one immutable canonical forestry generation. */
@Repository
class ForestryMapTileRepository {
    private static final Map<String, String> PROPERTY_SQL = Map.ofEntries(
        Map.entry("featureKey", "feature.feature_key AS \"featureKey\""),
        Map.entry("cadastralCode", "COALESCE(NULLIF(feature.properties->>'katastri_nr',''), NULLIF(feature.properties->>'katastritunnus',''), NULLIF(feature.properties->>'katastritunnus_',''), NULLIF(feature.properties->>'tunnus','')) AS \"cadastralCode\""),
        Map.entry("notificationKey", "COALESCE(NULLIF(feature.properties->>'teatise_nr',''), NULLIF(feature.properties->>'teatis_nr',''), NULLIF(feature.properties->>'number',''), feature.source_id, feature.feature_key) AS \"notificationKey\""),
        Map.entry("providerStatus", "COALESCE(NULLIF(feature.properties->>'staatus',''), NULLIF(feature.properties->>'status','')) AS \"providerStatus\""),
        Map.entry("archived", "(? = 'metsaregister:teatis_arhiiv') AS archived"),
        Map.entry("compartmentId", "COALESCE(NULLIF(feature.properties->>'sys_id',''), NULLIF(feature.properties->>'eraldis_id',''), NULLIF(feature.properties->>'eraldis_sys_id',''), NULLIF(feature.properties->>'parent_id',''), feature.source_id, feature.feature_key) AS \"compartmentId\""),
        Map.entry("code", "NULLIF(feature.properties->>'kood','') AS code"),
        Map.entry("cause", "COALESCE(NULLIF(feature.properties->>'kahjustuse_pohjus',''), NULLIF(feature.properties->>'pohjus','')) AS cause"),
        Map.entry("severity", "COALESCE(NULLIF(feature.properties->>'kahjustuse_aste',''), NULLIF(feature.properties->>'aste','')) AS severity")
    );

    private final DSLContext dsl;
    private final ForestryTileCatalogService catalog;
    private final MapTileAdmissionGate admission;

    ForestryMapTileRepository(DSLContext dsl, ForestryTileCatalogService catalog, MapTileAdmissionGate admission) {
        this.dsl = dsl;
        this.catalog = catalog;
        this.admission = admission;
    }

    TileResult tile(String layerKey, UUID forestryGenerationId, int z, int x, int y) {
        return admission.execute(Family.FORESTRY,
            () -> buildTile(layerKey, forestryGenerationId, z, x, y));
    }

    private TileResult buildTile(String layerKey, UUID forestryGenerationId, int z, int x, int y) {
        ForestryTileCatalogService.Layer contract = catalog.require(layerKey);
        validateCoordinates(contract, z, x, y);
        requirePublishedOrRetiredGeneration(forestryGenerationId);

        int candidateCount = candidateCount(layerKey, forestryGenerationId, z, x, y, contract.featureBudget());
        if (candidateCount > contract.featureBudget()) {
            throw new TileBudgetExceededException("Forestry tile feature budget exceeded for " + layerKey
                + " at " + z + "/" + x + "/" + y + ": " + candidateCount + ">" + contract.featureBudget());
        }

        List<String> selectColumns = new ArrayList<>();
        List<Object> propertyParams = new ArrayList<>();
        for (String property : contract.properties()) {
            String sql = PROPERTY_SQL.get(property);
            if (sql == null) throw new IllegalStateException("No MVT SQL mapping for property " + property);
            selectColumns.add(sql);
            if ("archived".equals(property)) propertyParams.add(layerKey);
        }
        selectColumns.add("ST_AsMVTGeom(ST_Transform(ST_Force2D(feature.geometry), 3857), bounds.geom, "
            + contract.extent() + ", " + contract.buffer() + ", true) AS geom");

        String sql = """
            WITH bounds AS (
                SELECT ST_TileEnvelope(?, ?, ?) AS geom
            ), encoded AS (
                SELECT %s
                  FROM metsaregister_forestry_generation_member member
                  JOIN metsaregister_wfs_feature feature
                    ON feature.layer_name = member.layer_name
                   AND feature.generation_id = member.layer_generation_id
                  CROSS JOIN bounds
                 WHERE member.forestry_generation_id = ?
                   AND member.source_key = 'METSAREGISTER_FORESTRY'
                   AND member.layer_name = ?
                   AND feature.geometry IS NOT NULL
                   AND feature.geometry && ST_Transform(bounds.geom, 3301)
                   AND ST_Intersects(feature.geometry, ST_Transform(bounds.geom, 3301))
            )
            SELECT COALESCE(ST_AsMVT(encoded, ?, ?, 'geom'), ''::bytea) AS tile
              FROM encoded
            """.formatted(String.join(",\n                       ", selectColumns));

        List<Object> params = new ArrayList<>();
        params.add(z);
        params.add(x);
        params.add(y);
        params.addAll(propertyParams);
        params.add(forestryGenerationId);
        params.add(layerKey);
        params.add(catalog.sourceLayer());
        params.add(contract.extent());

        byte[] bytes = dsl.fetchOne(sql, params.toArray()).get("tile", byte[].class);
        if (bytes == null) bytes = new byte[0];
        if (bytes.length > contract.byteBudget()) {
            throw new TileBudgetExceededException("Forestry tile byte budget exceeded for " + layerKey
                + " at " + z + "/" + x + "/" + y + ": " + bytes.length + ">" + contract.byteBudget());
        }
        return new TileResult(bytes, candidateCount, contract.byteBudget(), contract.featureBudget());
    }

    private int candidateCount(String layerKey, UUID generationId, int z, int x, int y, int budget) {
        Integer count = dsl.fetchOne("""
            WITH bounds AS (SELECT ST_TileEnvelope(?, ?, ?) AS geom)
            SELECT count(*)::int AS c
              FROM (
                    SELECT 1
                      FROM metsaregister_forestry_generation_member member
                      JOIN metsaregister_wfs_feature feature
                        ON feature.layer_name = member.layer_name
                       AND feature.generation_id = member.layer_generation_id
                      CROSS JOIN bounds
                     WHERE member.forestry_generation_id = ?
                       AND member.source_key = 'METSAREGISTER_FORESTRY'
                       AND member.layer_name = ?
                       AND feature.geometry IS NOT NULL
                       AND feature.geometry && ST_Transform(bounds.geom, 3301)
                     LIMIT ?
              ) candidates
            """, z, x, y, generationId, layerKey, budget + 1).get("c", Integer.class);
        return count == null ? 0 : count;
    }

    private void requirePublishedOrRetiredGeneration(UUID generationId) {
        if (generationId == null || dsl.fetchOne("""
            SELECT 1 AS present
              FROM geospatial_generation
             WHERE source_key = 'METSAREGISTER_FORESTRY'
               AND id = ?
               AND status IN ('PUBLISHED', 'RETIRED')
             LIMIT 1
            """, generationId) == null) {
            throw new IllegalArgumentException("Unknown or unpublished forestry generation: " + generationId);
        }
    }

    private static void validateCoordinates(ForestryTileCatalogService.Layer contract, int z, int x, int y) {
        if (z < contract.minZoom() || z > contract.maxZoom()) {
            throw new IllegalArgumentException("Zoom " + z + " is outside layer range "
                + contract.minZoom() + "-" + contract.maxZoom());
        }
        int max = (1 << z) - 1;
        if (x < 0 || y < 0 || x > max || y > max) {
            throw new IllegalArgumentException("Invalid XYZ tile coordinate");
        }
    }

    record TileResult(byte[] bytes, int featureCount, int byteBudget, int featureBudget) { }

    static class TileBudgetExceededException extends RuntimeException {
        TileBudgetExceededException(String message) {
            super(message);
        }
    }
}
