package ee.forestiq.geospatial;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ee.forestiq.geospatial.etak.EtakAdminLayerManifestService;
import ee.forestiq.map.MapTileAdmissionGate;
import ee.forestiq.map.MapTileAdmissionGate.Family;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Lightweight, current-generation map descriptors and bounded PostGIS MVT rendering. */
@Service
public class GeospatialOverlayService {
    private static final int FEATURE_BUDGET = 5_000;
    private static final int BYTE_BUDGET = 1024 * 1024;

    private final DSLContext dsl;
    private final EtakAdminLayerManifestService manifest;
    private final MapTileAdmissionGate admission;

    public GeospatialOverlayService(DSLContext dsl, EtakAdminLayerManifestService manifest,
                                    MapTileAdmissionGate admission) {
        this.dsl = dsl;
        this.manifest = manifest;
        this.admission = admission;
    }

    public List<LayerDescriptor> layers() {
        List<LayerDescriptor> result = new ArrayList<>();
        for (EtakAdminLayerManifestService.Layer layer : manifest.mapLayers()) {
            Record generation = current(layer.sourceKey());
            UUID generationId = generation == null ? null : generation.get("id", UUID.class);
            result.add(new LayerDescriptor(
                layer.sourceKey(),
                layer.title(),
                layer.datasetFamily(),
                layer.geometryType(),
                layer.minZoom(),
                layer.maxZoom(),
                layer.defaultOpacity(),
                layer.caveat(),
                generationId,
                generation == null ? null : generation.get("source_date", LocalDate.class),
                generationId == null ? null : "/api/v1/map/context/mvt/" + layer.sourceKey() + "/" + generationId + "/{z}/{x}/{y}.pbf",
                generationId != null));
        }
        return List.copyOf(result);
    }

    public byte[] tile(String sourceKey, UUID requestedGeneration, int z, int x, int y) {
        return admission.execute(Family.OVERLAY, () -> buildTile(sourceKey, requestedGeneration, z, x, y));
    }

    private byte[] buildTile(String sourceKey, UUID requestedGeneration, int z, int x, int y) {
        EtakAdminLayerManifestService.Layer layer = manifest.require(sourceKey);
        if (!layer.enabled() || !layer.mapVisible()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Map layer is not enabled");
        }
        validateTileCoordinates(layer, z, x, y);
        Record current = current(sourceKey);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No published generation is available for map layer");
        }
        UUID currentGeneration = current.get("id", UUID.class);
        if (!currentGeneration.equals(requestedGeneration)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Requested overlay generation is no longer current");
        }

        Record record = dsl.fetchOne("""
            WITH bounds AS (
                SELECT ST_TileEnvelope(?, ?, ?) AS b3857
            ), candidates AS (
                SELECT f.feature_id,
                       f.properties ->> 'tyyp' AS feature_type,
                       ST_AsMVTGeom(
                           ST_Transform(f.geom, 3857),
                           b.b3857,
                           4096,
                           64,
                           TRUE
                       ) AS geom
                  FROM geospatial_feature f
                  CROSS JOIN bounds b
                 WHERE f.source_key = ?
                   AND f.generation_id = ?
                   AND f.geom && ST_Transform(b.b3857, 3301)
                   AND ST_Intersects(f.geom, ST_Transform(b.b3857, 3301))
                 ORDER BY f.feature_id
                 LIMIT ?
            )
            SELECT count(*)::int AS feature_count,
                   COALESCE(ST_AsMVT(candidates, 'context', 4096, 'geom'), ''::bytea) AS tile
              FROM candidates
            """, z, x, y, sourceKey, requestedGeneration, FEATURE_BUDGET + 1);
        if (record == null) return new byte[0];
        Integer featureCount = record.get("feature_count", Integer.class);
        if (featureCount != null && featureCount > FEATURE_BUDGET) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Overlay feature budget exceeded; zoom in before requesting this layer");
        }
        byte[] tile = record.get("tile", byte[].class);
        byte[] safe = tile == null ? new byte[0] : tile;
        if (safe.length > BYTE_BUDGET) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Overlay byte budget exceeded; zoom in before requesting this layer");
        }
        return safe;
    }

    private Record current(String sourceKey) {
        return dsl.fetchOne("""
            SELECT g.id, g.source_date
              FROM geospatial_source_current c
              JOIN geospatial_generation g ON g.id = c.generation_id
             WHERE c.source_key = ? AND g.status = 'PUBLISHED'
            """, sourceKey);
    }

    private static void validateTileCoordinates(EtakAdminLayerManifestService.Layer layer, int z, int x, int y) {
        if (z < layer.minZoom() || z > layer.maxZoom()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zoom is outside layer budget");
        }
        if (z < 0 || z > 22) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid zoom");
        long span = 1L << z;
        if (x < 0 || y < 0 || x >= span || y >= span) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tile coordinates");
        }
    }

    public record LayerDescriptor(
        String sourceKey,
        String title,
        String datasetFamily,
        String geometryType,
        int minZoom,
        int maxZoom,
        double defaultOpacity,
        String caveat,
        UUID generationId,
        LocalDate sourceDate,
        String tileUrlTemplate,
        boolean available
    ) { }
}
