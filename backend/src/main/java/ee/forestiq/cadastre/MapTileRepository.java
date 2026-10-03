package ee.forestiq.cadastre;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import ee.forestiq.cadastre.ParcelMapSummary.ParcelOwnerSummary;

@Repository
public class MapTileRepository {

    private static final int OVERVIEW_GRID_CELLS = 12;
    private static final int OVERVIEW_MAX_FEATURES = 4_096;

    private final DSLContext dsl;
    private final MeterRegistry meterRegistry;

    /** Convenience constructor retained for focused repository tests. */
    public MapTileRepository(DSLContext dsl) {
        this(dsl, Metrics.globalRegistry);
    }

    @Autowired
    public MapTileRepository(DSLContext dsl, MeterRegistry meterRegistry) {
        this.dsl = dsl;
        this.meterRegistry = meterRegistry;
    }

    public byte[] landParcelsOverviewTile(UUID orgId, Optional<String> assignedSubjectRestriction,
                                          int z, int x, int y) {
        boolean restricted = assignedSubjectRestriction.isPresent();
        String scopeKey = assignedSubjectRestriction.map(subject -> "SUB:" + subject).orElse("ALL");
        var result = dsl.fetchOne("""
            WITH tile AS (
                SELECT ST_TileEnvelope(?::int, ?::int, ?::int) AS envelope
            ),
            bounds AS (
                SELECT envelope,
                       ST_XMin(envelope)::double precision AS min_x,
                       ST_YMin(envelope)::double precision AS min_y,
                       (ST_XMax(envelope) - ST_XMin(envelope)) / ?::double precision AS cell_size
                FROM tile
            ),
            scoped AS (
                SELECT
                    LEAST(? - 1, GREATEST(0,
                        floor((ST_X(pr.centroid_3857) - b.min_x) / b.cell_size)::int)) AS grid_x,
                    LEAST(? - 1, GREATEST(0,
                        floor((ST_Y(pr.centroid_3857) - b.min_y) / b.cell_size)::int)) AS grid_y,
                    current_owner.owner_status
                FROM land_parcel_render pr
                LEFT JOIN land_parcel_render_owner current_owner
                  ON current_owner.organization_id = pr.organization_id
                 AND current_owner.parcel_id = pr.parcel_id
                 AND current_owner.scope_key = ?
                CROSS JOIN bounds b
                WHERE pr.organization_id = ?
                  AND (? = false OR current_owner.owner_id IS NOT NULL)
                  AND pr.centroid_3857 && b.envelope
            ),
            grouped AS (
                SELECT s.grid_x,
                       s.grid_y,
                       s.owner_status,
                       count(*)::int AS parcel_count
                FROM scoped s
                GROUP BY s.grid_x, s.grid_y, s.owner_status
                ORDER BY parcel_count DESC, s.grid_x, s.grid_y, s.owner_status NULLS LAST
                LIMIT ?
            ),
            overview AS (
                SELECT g.grid_x,
                       g.grid_y,
                       g.owner_status,
                       g.parcel_count,
                       ST_SetSRID(
                           ST_MakePoint(
                               b.min_x + (g.grid_x + 0.5) * b.cell_size,
                               b.min_y + (g.grid_y + 0.5) * b.cell_size
                           ), 3857
                       ) AS render_point
                FROM grouped g
                CROSS JOIN bounds b
            ),
            mvtgeom AS (
                SELECT
                    (grid_x::text || ':' || grid_y::text || ':' || COALESCE(owner_status, 'NONE')) AS id,
                    owner_status,
                    parcel_count,
                    ST_AsMVTGeom(render_point, tile.envelope, 4096, 64, true) AS geom
                FROM overview
                CROSS JOIN tile
            )
            SELECT ST_AsMVT(mvtgeom, 'land_parcel_overview', 4096, 'geom') AS tile_bytes,
                   count(*)::int AS feature_count
            FROM mvtgeom
            """, z, x, y,
            OVERVIEW_GRID_CELLS, OVERVIEW_GRID_CELLS, OVERVIEW_GRID_CELLS,
            scopeKey, orgId, restricted, OVERVIEW_MAX_FEATURES);
        if (result == null) return new byte[0];

        Integer featureCountValue = result.get("feature_count", Integer.class);
        int featureCount = featureCountValue == null ? 0 : featureCountValue;
        recordFeatureCount("land_parcel_overview", z, featureCount);
        if (featureCount >= OVERVIEW_MAX_FEATURES) {
            Counter.builder("forestiq.map.tile.feature_cap_reached")
                .description("Protected tiles that reached the representation feature cap")
                .tag("layer", "land_parcel_overview")
                .tag("zoom", Integer.toString(z))
                .register(meterRegistry)
                .increment();
        }

        byte[] tile = result.get("tile_bytes", byte[].class);
        return tile == null ? new byte[0] : tile;
    }

    public byte[] landParcelsTile(UUID orgId, Optional<String> assignedSubjectRestriction, int z, int x, int y) {
        boolean restricted = assignedSubjectRestriction.isPresent();
        String scopeKey = assignedSubjectRestriction.map(subject -> "SUB:" + subject).orElse("ALL");
        int maxFeatures = maxFeatures(z);
        var result = dsl.fetchOne("""
            WITH tile AS (
                SELECT ST_TileEnvelope(?::int, ?::int, ?::int) AS envelope
            ),
            candidates AS (
                SELECT
                    pr.parcel_id,
                    pr.cadastral_code,
                    pr.area_hectares,
                    pr.marked,
                    CASE WHEN ?::int <= 10 THEN pr.boundary_low_3857 ELSE pr.boundary_3857 END AS render_geometry,
                    current_owner.owner_status,
                    current_owner.assigned_to_subject
                FROM land_parcel_render pr
                LEFT JOIN land_parcel_render_owner current_owner
                  ON current_owner.organization_id = pr.organization_id
                 AND current_owner.parcel_id = pr.parcel_id
                 AND current_owner.scope_key = ?
                CROSS JOIN tile
                WHERE pr.organization_id = ?
                  AND (? = false OR current_owner.owner_id IS NOT NULL)
                  AND pr.boundary_3857 && tile.envelope
                ORDER BY pr.parcel_id
                LIMIT ?
            ),
            mvtgeom AS (
                SELECT
                    c.parcel_id::text AS id,
                    c.cadastral_code,
                    c.area_hectares,
                    c.marked,
                    c.owner_status,
                    c.assigned_to_subject,
                    ST_AsMVTGeom(c.render_geometry, tile.envelope, 4096, 64, true) AS geom
                FROM candidates c
                CROSS JOIN tile
            )
            SELECT ST_AsMVT(mvtgeom, 'land_parcel', 4096, 'geom') AS tile_bytes,
                   count(*)::int AS feature_count
            FROM mvtgeom
            """, z, x, y, z, scopeKey, orgId, restricted, maxFeatures);
        if (result == null) return new byte[0];

        Integer featureCountValue = result.get("feature_count", Integer.class);
        int featureCount = featureCountValue == null ? 0 : featureCountValue;
        recordFeatureCount("land_parcel", z, featureCount);
        if (featureCount >= maxFeatures) {
            Counter.builder("forestiq.map.tile.feature_cap_reached")
                .description("Protected tiles that reached the representation feature cap")
                .tag("layer", "land_parcel")
                .tag("zoom", Integer.toString(z))
                .register(meterRegistry)
                .increment();
        }

        byte[] tile = result.get("tile_bytes", byte[].class);
        return tile == null ? new byte[0] : tile;
    }

    private void recordFeatureCount(String layer, int zoom, int featureCount) {
        DistributionSummary.builder("forestiq.map.tile.features")
            .description("Features encoded into protected vector tiles")
            .tag("layer", layer)
            .tag("zoom", Integer.toString(zoom))
            .register(meterRegistry)
            .record(featureCount);
    }

    static int maxFeatures(int zoom) {
        if (zoom <= 8) return 1_500;
        if (zoom <= 12) return 5_000;
        return 10_000;
    }

    static int overviewGridCells() {
        return OVERVIEW_GRID_CELLS;
    }

    static int overviewMaxFeatures() {
        return OVERVIEW_MAX_FEATURES;
    }

    static boolean usesPrecomputedLowZoomGeometry(int zoom) {
        return zoom <= 10;
    }

    public Optional<ParcelMapSummary> landParcelSummary(UUID orgId, Optional<String> assignedSubjectRestriction, String cadastralCode) {
        boolean restricted = assignedSubjectRestriction.isPresent();
        String assignedSubject = assignedSubjectRestriction.orElse("");
        var record = dsl.fetchOne("""
            SELECT p.id AS parcel_id,
                   p.cadastral_code,
                   p.name,
                   p.address,
                   p.postal,
                   p.reg_nr,
                   p.parcel_type,
                   p.mk_date,
                   p.area_hectares,
                   p.yard_area,
                   p.forest_area,
                   p.arable_area,
                   p.meadow_area,
                   p.underwater_area,
                   p.buildings_area,
                   p.other_area,
                   p.county,
                   p.municipality,
                   p.marked,
                   ST_X(ST_PointOnSurface(map_geometry.safe_boundary)) AS centroid_longitude,
                   ST_Y(ST_PointOnSurface(map_geometry.safe_boundary)) AS centroid_latitude,
                   ST_XMin(map_geometry.safe_boundary) AS bbox_west,
                   ST_YMin(map_geometry.safe_boundary) AS bbox_south,
                   ST_XMax(map_geometry.safe_boundary) AS bbox_east,
                   ST_YMax(map_geometry.safe_boundary) AS bbox_north,
                   current_owner.id AS owner_id,
                   current_owner.display_name,
                   current_owner.status,
                   current_owner.assigned_to_subject,
                   current_owner.phone,
                   (SELECT count(*)::int
                      FROM cadastre_sub_part sp
                     WHERE sp.organization_id = p.organization_id
                       AND sp.cadastral_code = p.cadastral_code) AS sub_part_count,
                   (SELECT count(*)::int
                      FROM cadastre_notification n
                     WHERE n.organization_id = p.organization_id
                       AND n.cadastral_code = p.cadastral_code) AS notification_count,
                   (SELECT count(*)::int
                      FROM cadastre_notification n
                     WHERE n.organization_id = p.organization_id
                       AND n.cadastral_code = p.cadastral_code
                       AND COALESCE(n.archived, false) = false) AS active_notification_count,
                   (SELECT count(*)::int
                      FROM forest_registry_feature f
                     WHERE f.organization_id = p.organization_id
                       AND f.cadastral_code = p.cadastral_code) AS forest_feature_count
            FROM land_parcel p
            LEFT JOIN LATERAL (
                SELECT o.id, o.display_name, o.status, o.assigned_to_subject, o.phone
                FROM owner_parcel_relation opr
                JOIN owner o
                  ON o.id = opr.owner_id
                 AND o.organization_id = opr.organization_id
                WHERE opr.organization_id = p.organization_id
                  AND opr.parcel_id = p.id
                  AND opr.active = true
                  AND (? = false OR o.assigned_to_subject = ?)
                ORDER BY CASE o.status
                    WHEN 'CONTRACTED' THEN 10
                    WHEN 'NEGOTIATION' THEN 20
                    WHEN 'VALUED' THEN 30
                    WHEN 'EVALUATION' THEN 40
                    WHEN 'WAITS_FOR_EVALUATION' THEN 50
                    WHEN 'INTERESTED' THEN 60
                    WHEN 'CONTACTED' THEN 70
                    WHEN 'ASSIGNED' THEN 80
                    WHEN 'NEW' THEN 90
                    WHEN 'NOT_INTERESTED' THEN 100
                    WHEN 'DECEASED' THEN 110
                    ELSE 120
                  END,
                  opr.created_at ASC,
                  o.id ASC
                LIMIT 1
            ) current_owner ON true
            LEFT JOIN LATERAL (
                SELECT CASE
                    WHEN p.boundary IS NULL OR ST_IsEmpty(p.boundary) THEN NULL
                    ELSE ST_CollectionExtract(ST_MakeValid(ST_Force2D(p.boundary)), 3)
                END AS safe_boundary
            ) map_geometry ON true
            WHERE p.organization_id = ?
              AND p.cadastral_code = ?
              AND (? = false OR current_owner.id IS NOT NULL)
            """, restricted, assignedSubject, orgId, cadastralCode, restricted);
        if (record == null) {
            return Optional.empty();
        }

        UUID parcelId = record.get("parcel_id", UUID.class);
        List<ParcelOwnerSummary> owners = dsl.fetch("""
            SELECT o.id AS owner_id,
                   o.display_name,
                   o.status,
                   o.assigned_to_subject,
                   o.phone
            FROM owner_parcel_relation opr
            JOIN owner o
              ON o.id = opr.owner_id
             AND o.organization_id = opr.organization_id
            WHERE opr.organization_id = ?
              AND opr.parcel_id = ?
              AND opr.active = true
              AND (? = false OR o.assigned_to_subject = ?)
            ORDER BY CASE o.status
                WHEN 'CONTRACTED' THEN 10
                WHEN 'NEGOTIATION' THEN 20
                WHEN 'VALUED' THEN 30
                WHEN 'EVALUATION' THEN 40
                WHEN 'WAITS_FOR_EVALUATION' THEN 50
                WHEN 'INTERESTED' THEN 60
                WHEN 'CONTACTED' THEN 70
                WHEN 'ASSIGNED' THEN 80
                WHEN 'NEW' THEN 90
                WHEN 'NOT_INTERESTED' THEN 100
                WHEN 'DECEASED' THEN 110
                ELSE 120
              END,
              opr.created_at ASC,
              o.id ASC
            """, orgId, parcelId, restricted, assignedSubject).map(owner -> new ParcelOwnerSummary(
                owner.get("owner_id", UUID.class),
                owner.get("display_name", String.class),
                owner.get("status", String.class),
                owner.get("assigned_to_subject", String.class),
                "OWNER_PARCEL_RELATION"));

        return Optional.of(new ParcelMapSummary(
            record.get("cadastral_code", String.class),
            record.get("owner_id", UUID.class),
            record.get("display_name", String.class),
            record.get("status", String.class),
            record.get("assigned_to_subject", String.class),
            record.get("phone", String.class),
            record.get("name", String.class),
            record.get("address", String.class),
            record.get("postal", String.class),
            record.get("reg_nr", String.class),
            record.get("parcel_type", String.class),
            record.get("mk_date", Long.class),
            record.get("area_hectares", BigDecimal.class),
            record.get("yard_area", BigDecimal.class),
            record.get("forest_area", BigDecimal.class),
            record.get("arable_area", BigDecimal.class),
            record.get("meadow_area", BigDecimal.class),
            record.get("underwater_area", BigDecimal.class),
            record.get("buildings_area", BigDecimal.class),
            record.get("other_area", BigDecimal.class),
            record.get("county", String.class),
            record.get("municipality", String.class),
            Boolean.TRUE.equals(record.get("marked", Boolean.class)),
            record.get("centroid_longitude", Double.class),
            record.get("centroid_latitude", Double.class),
            record.get("bbox_west", Double.class),
            record.get("bbox_south", Double.class),
            record.get("bbox_east", Double.class),
            record.get("bbox_north", Double.class),
            owners,
            null,
            null,
            owners.size(),
            intValue(record.get("sub_part_count", Integer.class)),
            intValue(record.get("active_notification_count", Integer.class)),
            intValue(record.get("notification_count", Integer.class)),
            intValue(record.get("forest_feature_count", Integer.class))));
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
