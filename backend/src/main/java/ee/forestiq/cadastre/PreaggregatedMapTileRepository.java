package ee.forestiq.cadastre;

import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * Production map repository variant that serves z6-z10 overview tiles from the
 * preaggregated read model introduced by V34. The base repository remains useful
 * for focused tests and as documentation of the previous runtime aggregation.
 */
@Repository
@Primary
public class PreaggregatedMapTileRepository extends MapTileRepository {

    private static final int GRID_CELLS = 12;
    private static final int MAX_FEATURES = 4_096;

    private final DSLContext dsl;
    private final MeterRegistry meterRegistry;

    public PreaggregatedMapTileRepository(DSLContext dsl, MeterRegistry meterRegistry) {
        super(dsl, meterRegistry);
        this.dsl = dsl;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public byte[] landParcelsOverviewTile(UUID orgId, Optional<String> assignedSubjectRestriction,
                                          int z, int x, int y) {
        String scopeKey = assignedSubjectRestriction.map(subject -> "SUB:" + subject).orElse("ALL");
        var result = dsl.fetchOne("""
            WITH tile AS (
                SELECT ST_TileEnvelope(?::int, ?::int, ?::int) AS envelope
            ),
            selected AS (
                SELECT c.grid_x,
                       c.grid_y,
                       NULLIF(c.owner_status_key, 'NONE') AS owner_status,
                       c.parcel_count
                  FROM land_parcel_overview_cell c
                 WHERE c.organization_id = ?
                   AND c.scope_key = ?
                   AND c.zoom = ?
                   AND c.tile_x = ?
                   AND c.tile_y = ?
                 ORDER BY c.parcel_count DESC, c.grid_x, c.grid_y, c.owner_status_key
                 LIMIT ?
            ),
            overview AS (
                SELECT s.grid_x,
                       s.grid_y,
                       s.owner_status,
                       s.parcel_count,
                       ST_SetSRID(
                           ST_MakePoint(
                               ST_XMin(t.envelope)
                                   + (s.grid_x + 0.5) * (ST_XMax(t.envelope) - ST_XMin(t.envelope)) / ?::double precision,
                               ST_YMax(t.envelope)
                                   - (s.grid_y + 0.5) * (ST_YMax(t.envelope) - ST_YMin(t.envelope)) / ?::double precision
                           ), 3857
                       ) AS render_point
                  FROM selected s
                  CROSS JOIN tile t
            ),
            mvtgeom AS (
                SELECT (grid_x::text || ':' || grid_y::text || ':' || COALESCE(owner_status, 'NONE')) AS id,
                       owner_status,
                       parcel_count,
                       ST_AsMVTGeom(render_point, t.envelope, 4096, 64, true) AS geom
                  FROM overview
                  CROSS JOIN tile t
            )
            SELECT ST_AsMVT(mvtgeom, 'land_parcel_overview', 4096, 'geom') AS tile_bytes,
                   count(*)::int AS feature_count
              FROM mvtgeom
            """, z, x, y, orgId, scopeKey, z, x, y, MAX_FEATURES, GRID_CELLS, GRID_CELLS);

        if (result == null) return new byte[0];
        Integer value = result.get("feature_count", Integer.class);
        int featureCount = value == null ? 0 : value;
        DistributionSummary.builder("forestiq.map.tile.features")
            .description("Features encoded into protected vector tiles")
            .tag("layer", "land_parcel_overview")
            .tag("zoom", Integer.toString(z))
            .register(meterRegistry)
            .record(featureCount);
        if (featureCount >= MAX_FEATURES) {
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
}
