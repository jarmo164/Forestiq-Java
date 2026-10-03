-- Preaggregate low-zoom parcel overview cells so a cold z6-z10 tile does not
-- spatially scan/group the parcel render model on every request. Canonical
-- parcel/ownership data remains in the existing OLTP tables; this is disposable
-- read-model state and can be rebuilt per organization.

CREATE TABLE land_parcel_overview_contribution (
    organization_id UUID NOT NULL,
    parcel_id UUID NOT NULL,
    scope_key VARCHAR(300) NOT NULL,
    zoom SMALLINT NOT NULL CHECK (zoom BETWEEN 6 AND 10),
    tile_x INTEGER NOT NULL,
    tile_y INTEGER NOT NULL,
    grid_x SMALLINT NOT NULL CHECK (grid_x BETWEEN 0 AND 11),
    grid_y SMALLINT NOT NULL CHECK (grid_y BETWEEN 0 AND 11),
    owner_status_key VARCHAR(32) NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, parcel_id, scope_key, zoom)
);

CREATE INDEX land_parcel_overview_contribution_cell_idx
    ON land_parcel_overview_contribution
       (organization_id, scope_key, zoom, tile_x, tile_y,
        grid_x, grid_y, owner_status_key);

CREATE TABLE land_parcel_overview_cell (
    organization_id UUID NOT NULL,
    scope_key VARCHAR(300) NOT NULL,
    zoom SMALLINT NOT NULL CHECK (zoom BETWEEN 6 AND 10),
    tile_x INTEGER NOT NULL,
    tile_y INTEGER NOT NULL,
    grid_x SMALLINT NOT NULL CHECK (grid_x BETWEEN 0 AND 11),
    grid_y SMALLINT NOT NULL CHECK (grid_y BETWEEN 0 AND 11),
    owner_status_key VARCHAR(32) NOT NULL,
    parcel_count INTEGER NOT NULL CHECK (parcel_count > 0),
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, scope_key, zoom, tile_x, tile_y,
                 grid_x, grid_y, owner_status_key)
);

CREATE INDEX land_parcel_overview_cell_lookup_idx
    ON land_parcel_overview_cell
       (organization_id, scope_key, zoom, tile_x, tile_y);

CREATE OR REPLACE FUNCTION refresh_land_parcel_overview(
    p_organization_id UUID,
    p_parcel_ids UUID[] DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    world_half CONSTANT DOUBLE PRECISION := 20037508.342789244;
BEGIN
    -- Full rebuild is used after large set-based changes. Small OLTP changes use
    -- the contribution table to identify only cells whose aggregate changed.
    IF p_parcel_ids IS NULL THEN
        DELETE FROM land_parcel_overview_contribution
         WHERE organization_id = p_organization_id;

        INSERT INTO land_parcel_overview_contribution
            (organization_id, parcel_id, scope_key, zoom, tile_x, tile_y,
             grid_x, grid_y, owner_status_key, refreshed_at)
        WITH scopes AS (
            SELECT pr.organization_id,
                   pr.parcel_id,
                   pr.centroid_3857,
                   'ALL'::varchar(300) AS scope_key,
                   COALESCE(ro.owner_status, 'NONE')::varchar(32) AS owner_status_key
              FROM land_parcel_render pr
              LEFT JOIN land_parcel_render_owner ro
                ON ro.organization_id = pr.organization_id
               AND ro.parcel_id = pr.parcel_id
               AND ro.scope_key = 'ALL'
             WHERE pr.organization_id = p_organization_id
            UNION ALL
            SELECT pr.organization_id,
                   pr.parcel_id,
                   pr.centroid_3857,
                   ro.scope_key,
                   COALESCE(ro.owner_status, 'NONE')::varchar(32)
              FROM land_parcel_render pr
              JOIN land_parcel_render_owner ro
                ON ro.organization_id = pr.organization_id
               AND ro.parcel_id = pr.parcel_id
               AND ro.scope_key LIKE 'SUB:%'
             WHERE pr.organization_id = p_organization_id
        ), normalized AS (
            SELECT s.*,
                   z::smallint AS zoom,
                   ((ST_X(s.centroid_3857) + world_half) / (2.0 * world_half)) * power(2.0, z) AS nx,
                   ((world_half - ST_Y(s.centroid_3857)) / (2.0 * world_half)) * power(2.0, z) AS ny
              FROM scopes s
              CROSS JOIN generate_series(6, 10) z
        ), cells AS (
            SELECT n.*,
                   LEAST((power(2.0, zoom)::int - 1), GREATEST(0, floor(nx)::int)) AS tx,
                   LEAST((power(2.0, zoom)::int - 1), GREATEST(0, floor(ny)::int)) AS ty
              FROM normalized n
        )
        SELECT organization_id,
               parcel_id,
               scope_key,
               zoom,
               tx,
               ty,
               LEAST(11, GREATEST(0, floor((nx - floor(nx)) * 12.0)::int))::smallint,
               LEAST(11, GREATEST(0, floor((ny - floor(ny)) * 12.0)::int))::smallint,
               owner_status_key,
               CURRENT_TIMESTAMP
          FROM cells;

        DELETE FROM land_parcel_overview_cell
         WHERE organization_id = p_organization_id;

        INSERT INTO land_parcel_overview_cell
            (organization_id, scope_key, zoom, tile_x, tile_y, grid_x, grid_y,
             owner_status_key, parcel_count, refreshed_at)
        SELECT organization_id, scope_key, zoom, tile_x, tile_y, grid_x, grid_y,
               owner_status_key, count(*)::int, CURRENT_TIMESTAMP
          FROM land_parcel_overview_contribution
         WHERE organization_id = p_organization_id
         GROUP BY organization_id, scope_key, zoom, tile_x, tile_y,
                  grid_x, grid_y, owner_status_key;
        RETURN;
    END IF;

    CREATE TEMP TABLE IF NOT EXISTS forestiq_overview_changed_cells (
        organization_id UUID NOT NULL,
        scope_key VARCHAR(300) NOT NULL,
        zoom SMALLINT NOT NULL,
        tile_x INTEGER NOT NULL,
        tile_y INTEGER NOT NULL,
        grid_x SMALLINT NOT NULL,
        grid_y SMALLINT NOT NULL,
        owner_status_key VARCHAR(32) NOT NULL,
        PRIMARY KEY (organization_id, scope_key, zoom, tile_x, tile_y,
                     grid_x, grid_y, owner_status_key)
    ) ON COMMIT DELETE ROWS;
    TRUNCATE forestiq_overview_changed_cells;

    INSERT INTO forestiq_overview_changed_cells
    SELECT DISTINCT organization_id, scope_key, zoom, tile_x, tile_y,
                    grid_x, grid_y, owner_status_key
      FROM land_parcel_overview_contribution
     WHERE organization_id = p_organization_id
       AND parcel_id = ANY(p_parcel_ids)
    ON CONFLICT DO NOTHING;

    DELETE FROM land_parcel_overview_contribution
     WHERE organization_id = p_organization_id
       AND parcel_id = ANY(p_parcel_ids);

    INSERT INTO land_parcel_overview_contribution
        (organization_id, parcel_id, scope_key, zoom, tile_x, tile_y,
         grid_x, grid_y, owner_status_key, refreshed_at)
    WITH scopes AS (
        SELECT pr.organization_id,
               pr.parcel_id,
               pr.centroid_3857,
               'ALL'::varchar(300) AS scope_key,
               COALESCE(ro.owner_status, 'NONE')::varchar(32) AS owner_status_key
          FROM land_parcel_render pr
          LEFT JOIN land_parcel_render_owner ro
            ON ro.organization_id = pr.organization_id
           AND ro.parcel_id = pr.parcel_id
           AND ro.scope_key = 'ALL'
         WHERE pr.organization_id = p_organization_id
           AND pr.parcel_id = ANY(p_parcel_ids)
        UNION ALL
        SELECT pr.organization_id,
               pr.parcel_id,
               pr.centroid_3857,
               ro.scope_key,
               COALESCE(ro.owner_status, 'NONE')::varchar(32)
          FROM land_parcel_render pr
          JOIN land_parcel_render_owner ro
            ON ro.organization_id = pr.organization_id
           AND ro.parcel_id = pr.parcel_id
           AND ro.scope_key LIKE 'SUB:%'
         WHERE pr.organization_id = p_organization_id
           AND pr.parcel_id = ANY(p_parcel_ids)
    ), normalized AS (
        SELECT s.*,
               z::smallint AS zoom,
               ((ST_X(s.centroid_3857) + world_half) / (2.0 * world_half)) * power(2.0, z) AS nx,
               ((world_half - ST_Y(s.centroid_3857)) / (2.0 * world_half)) * power(2.0, z) AS ny
          FROM scopes s
          CROSS JOIN generate_series(6, 10) z
    ), cells AS (
        SELECT n.*,
               LEAST((power(2.0, zoom)::int - 1), GREATEST(0, floor(nx)::int)) AS tx,
               LEAST((power(2.0, zoom)::int - 1), GREATEST(0, floor(ny)::int)) AS ty
          FROM normalized n
    )
    SELECT organization_id,
           parcel_id,
           scope_key,
           zoom,
           tx,
           ty,
           LEAST(11, GREATEST(0, floor((nx - floor(nx)) * 12.0)::int))::smallint,
           LEAST(11, GREATEST(0, floor((ny - floor(ny)) * 12.0)::int))::smallint,
           owner_status_key,
           CURRENT_TIMESTAMP
      FROM cells;

    INSERT INTO forestiq_overview_changed_cells
    SELECT DISTINCT organization_id, scope_key, zoom, tile_x, tile_y,
                    grid_x, grid_y, owner_status_key
      FROM land_parcel_overview_contribution
     WHERE organization_id = p_organization_id
       AND parcel_id = ANY(p_parcel_ids)
    ON CONFLICT DO NOTHING;

    DELETE FROM land_parcel_overview_cell c
     USING forestiq_overview_changed_cells k
     WHERE c.organization_id = k.organization_id
       AND c.scope_key = k.scope_key
       AND c.zoom = k.zoom
       AND c.tile_x = k.tile_x
       AND c.tile_y = k.tile_y
       AND c.grid_x = k.grid_x
       AND c.grid_y = k.grid_y
       AND c.owner_status_key = k.owner_status_key;

    INSERT INTO land_parcel_overview_cell
        (organization_id, scope_key, zoom, tile_x, tile_y, grid_x, grid_y,
         owner_status_key, parcel_count, refreshed_at)
    SELECT c.organization_id, c.scope_key, c.zoom, c.tile_x, c.tile_y,
           c.grid_x, c.grid_y, c.owner_status_key, count(*)::int, CURRENT_TIMESTAMP
      FROM land_parcel_overview_contribution c
      JOIN forestiq_overview_changed_cells k
        ON k.organization_id = c.organization_id
       AND k.scope_key = c.scope_key
       AND k.zoom = c.zoom
       AND k.tile_x = c.tile_x
       AND k.tile_y = c.tile_y
       AND k.grid_x = c.grid_x
       AND k.grid_y = c.grid_y
       AND k.owner_status_key = c.owner_status_key
     GROUP BY c.organization_id, c.scope_key, c.zoom, c.tile_x, c.tile_y,
              c.grid_x, c.grid_y, c.owner_status_key;
END
$$;

-- Keep overview contributions synchronized whenever owner visibility/status is
-- refreshed. The existing statement triggers already route relation/owner
-- changes through this function, so the overview inherits the same safety model.
CREATE OR REPLACE FUNCTION refresh_land_parcel_render_owners(
    p_organization_id UUID,
    p_parcel_ids UUID[] DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM land_parcel_render_owner ro
     WHERE ro.organization_id = p_organization_id
       AND (p_parcel_ids IS NULL OR ro.parcel_id = ANY(p_parcel_ids));

    WITH ranked AS (
        SELECT r.organization_id,
               r.parcel_id,
               o.id AS owner_id,
               o.status AS owner_status,
               o.assigned_to_subject,
               ROW_NUMBER() OVER (
                   PARTITION BY r.organization_id, r.parcel_id
                   ORDER BY forestiq_owner_status_rank(o.status), r.created_at, o.id
               ) AS rank_all,
               CASE WHEN o.assigned_to_subject IS NULL THEN NULL ELSE
                   ROW_NUMBER() OVER (
                       PARTITION BY r.organization_id, r.parcel_id, o.assigned_to_subject
                       ORDER BY forestiq_owner_status_rank(o.status), r.created_at, o.id
                   )
               END AS rank_subject
          FROM owner_parcel_relation r
          JOIN owner o
            ON o.organization_id = r.organization_id
           AND o.id = r.owner_id
          JOIN land_parcel_render pr
            ON pr.organization_id = r.organization_id
           AND pr.parcel_id = r.parcel_id
         WHERE r.organization_id = p_organization_id
           AND r.active = TRUE
           AND (p_parcel_ids IS NULL OR r.parcel_id = ANY(p_parcel_ids))
    )
    INSERT INTO land_parcel_render_owner
        (organization_id, parcel_id, scope_key, owner_id, owner_status,
         assigned_to_subject, refreshed_at)
    SELECT organization_id, parcel_id, 'ALL', owner_id, owner_status,
           assigned_to_subject, CURRENT_TIMESTAMP
      FROM ranked
     WHERE rank_all = 1
    UNION ALL
    SELECT organization_id, parcel_id, 'SUB:' || assigned_to_subject,
           owner_id, owner_status, assigned_to_subject, CURRENT_TIMESTAMP
      FROM ranked
     WHERE assigned_to_subject IS NOT NULL AND rank_subject = 1;

    PERFORM refresh_land_parcel_overview(p_organization_id, p_parcel_ids);
END
$$;

-- Parcel deletes remove the render row first, then remove/reaggregate the stale
-- contribution cells using the preserved parcel ids.
CREATE OR REPLACE FUNCTION forestiq_land_parcel_render_delete_stmt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    changed RECORD;
BEGIN
    DELETE FROM land_parcel_render pr
     USING parcel_old_rows old_row
     WHERE pr.organization_id = old_row.organization_id
       AND pr.parcel_id = old_row.id;

    FOR changed IN
        SELECT organization_id, array_agg(id) AS ids
          FROM parcel_old_rows
         GROUP BY organization_id
    LOOP
        PERFORM refresh_land_parcel_overview(changed.organization_id, changed.ids);
    END LOOP;
    RETURN NULL;
END
$$;

-- Initial rebuild for all organizations already present in the render model.
DO $$
DECLARE
    org RECORD;
BEGIN
    FOR org IN SELECT DISTINCT organization_id FROM land_parcel_render LOOP
        PERFORM refresh_land_parcel_overview(org.organization_id, NULL);
    END LOOP;
END
$$;

ANALYZE land_parcel_overview_contribution;
ANALYZE land_parcel_overview_cell;
