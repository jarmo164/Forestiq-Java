-- Disposable map read model. Canonical parcel/owner/relation data remains in
-- land_parcel, owner and owner_parcel_relation.
CREATE TABLE land_parcel_render (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    parcel_id UUID NOT NULL,
    cadastral_code VARCHAR(32) NOT NULL,
    area_hectares NUMERIC(12, 4) NOT NULL,
    marked BOOLEAN NOT NULL DEFAULT FALSE,
    boundary_3857 geometry(Geometry, 3857) NOT NULL,
    boundary_low_3857 geometry(Geometry, 3857) NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, parcel_id),
    CONSTRAINT land_parcel_render_parcel_fk
        FOREIGN KEY (parcel_id) REFERENCES land_parcel(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX land_parcel_render_org_cadastre_idx
    ON land_parcel_render (organization_id, cadastral_code);
CREATE INDEX land_parcel_render_boundary_gix
    ON land_parcel_render USING GIST (boundary_3857);

-- One pre-ranked owner per visibility scope. `ALL` is the unrestricted map
-- view; `SUB:<subject>` is the best active owner after applying assignee scope.
-- Keeping scopes separately preserves the old "filter then rank" semantics.
CREATE TABLE land_parcel_render_owner (
    organization_id UUID NOT NULL,
    parcel_id UUID NOT NULL,
    scope_key VARCHAR(300) NOT NULL,
    owner_id UUID NOT NULL,
    owner_status VARCHAR(32) NOT NULL,
    assigned_to_subject VARCHAR(255),
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, parcel_id, scope_key),
    CONSTRAINT land_parcel_render_owner_render_fk
        FOREIGN KEY (organization_id, parcel_id)
        REFERENCES land_parcel_render(organization_id, parcel_id) ON DELETE CASCADE
);
CREATE INDEX land_parcel_render_owner_scope_idx
    ON land_parcel_render_owner (organization_id, scope_key, parcel_id);

CREATE OR REPLACE FUNCTION forestiq_owner_status_rank(p_status TEXT)
RETURNS INTEGER
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE p_status
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
    END
$$;

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
END
$$;

-- Geometry projection/simplification is paid when canonical data changes, not
-- on every vector-tile miss. 20 m is the first low/mid-zoom band; #125 records
-- real polygon complexity/tile timing so another band can be justified later.
CREATE OR REPLACE FUNCTION refresh_land_parcel_render(
    p_organization_id UUID,
    p_parcel_ids UUID[] DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM land_parcel_render pr
     WHERE pr.organization_id = p_organization_id
       AND (p_parcel_ids IS NULL OR pr.parcel_id = ANY(p_parcel_ids))
       AND NOT EXISTS (
           SELECT 1
             FROM land_parcel p
            WHERE p.organization_id = pr.organization_id
              AND p.id = pr.parcel_id
              AND p.boundary IS NOT NULL
              AND NOT ST_IsEmpty(p.boundary)
       );

    INSERT INTO land_parcel_render
        (organization_id, parcel_id, cadastral_code, area_hectares, marked,
         boundary_3857, boundary_low_3857, source_updated_at, refreshed_at)
    SELECT p.organization_id,
           p.id,
           p.cadastral_code,
           p.area_hectares,
           COALESCE(p.marked, FALSE),
           projected.geom_3857,
           ST_SimplifyPreserveTopology(projected.geom_3857, 20.0),
           p.updated_at,
           CURRENT_TIMESTAMP
      FROM land_parcel p
      CROSS JOIN LATERAL (
          SELECT ST_Transform(ST_Force2D(p.boundary), 3857) AS geom_3857
      ) projected
     WHERE p.organization_id = p_organization_id
       AND p.boundary IS NOT NULL
       AND NOT ST_IsEmpty(p.boundary)
       AND (p_parcel_ids IS NULL OR p.id = ANY(p_parcel_ids))
    ON CONFLICT (organization_id, parcel_id) DO UPDATE SET
        cadastral_code = EXCLUDED.cadastral_code,
        area_hectares = EXCLUDED.area_hectares,
        marked = EXCLUDED.marked,
        boundary_3857 = EXCLUDED.boundary_3857,
        boundary_low_3857 = EXCLUDED.boundary_low_3857,
        source_updated_at = EXCLUDED.source_updated_at,
        refreshed_at = EXCLUDED.refreshed_at;

    PERFORM refresh_land_parcel_render_owners(p_organization_id, p_parcel_ids);
END
$$;

CREATE OR REPLACE FUNCTION rebuild_land_parcel_render(p_organization_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM refresh_land_parcel_render(p_organization_id, NULL);
END
$$;

CREATE OR REPLACE FUNCTION rebuild_land_parcel_render_owners(p_organization_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM refresh_land_parcel_render_owners(p_organization_id, NULL);
END
$$;

-- Small OLTP statements refresh only affected parcels. Large statements switch
-- to one full set-based rebuild, avoiding both per-row triggers and huge arrays.
CREATE OR REPLACE FUNCTION forestiq_land_parcel_render_insert_stmt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    changed RECORD;
    ids UUID[];
BEGIN
    FOR changed IN
        SELECT organization_id, count(*) AS changed_count
          FROM parcel_new_rows
         WHERE boundary IS NOT NULL AND NOT ST_IsEmpty(boundary)
         GROUP BY organization_id
    LOOP
        IF changed.changed_count >= 50000 THEN
            PERFORM rebuild_land_parcel_render(changed.organization_id);
        ELSE
            SELECT array_agg(id) INTO ids FROM parcel_new_rows
             WHERE organization_id = changed.organization_id
               AND boundary IS NOT NULL AND NOT ST_IsEmpty(boundary);
            PERFORM refresh_land_parcel_render(changed.organization_id, ids);
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION forestiq_land_parcel_render_update_stmt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    changed RECORD;
    ids UUID[];
BEGIN
    FOR changed IN
        WITH changed_rows AS (
            SELECT n.organization_id, n.id
              FROM parcel_new_rows n
              JOIN parcel_old_rows o ON o.id = n.id
             WHERE n.organization_id IS DISTINCT FROM o.organization_id
                OR n.cadastral_code IS DISTINCT FROM o.cadastral_code
                OR n.area_hectares IS DISTINCT FROM o.area_hectares
                OR n.marked IS DISTINCT FROM o.marked
                OR n.boundary IS DISTINCT FROM o.boundary
            UNION
            SELECT o.organization_id, o.id
              FROM parcel_old_rows o
              LEFT JOIN parcel_new_rows n ON n.id = o.id
             WHERE n.id IS NULL OR n.organization_id IS DISTINCT FROM o.organization_id
        )
        SELECT organization_id, count(*) AS changed_count
          FROM changed_rows GROUP BY organization_id
    LOOP
        IF changed.changed_count >= 50000 THEN
            PERFORM rebuild_land_parcel_render(changed.organization_id);
        ELSE
            WITH changed_rows AS (
                SELECT n.organization_id, n.id
                  FROM parcel_new_rows n
                  JOIN parcel_old_rows o ON o.id = n.id
                 WHERE n.organization_id IS DISTINCT FROM o.organization_id
                    OR n.cadastral_code IS DISTINCT FROM o.cadastral_code
                    OR n.area_hectares IS DISTINCT FROM o.area_hectares
                    OR n.marked IS DISTINCT FROM o.marked
                    OR n.boundary IS DISTINCT FROM o.boundary
                UNION
                SELECT o.organization_id, o.id
                  FROM parcel_old_rows o
                  LEFT JOIN parcel_new_rows n ON n.id = o.id
                 WHERE n.id IS NULL OR n.organization_id IS DISTINCT FROM o.organization_id
            )
            SELECT array_agg(id) INTO ids FROM changed_rows
             WHERE organization_id = changed.organization_id;
            PERFORM refresh_land_parcel_render(changed.organization_id, ids);
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION forestiq_land_parcel_render_delete_stmt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM land_parcel_render pr
     USING parcel_old_rows old_row
     WHERE pr.organization_id = old_row.organization_id
       AND pr.parcel_id = old_row.id;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION forestiq_relation_render_insert_stmt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    changed RECORD;
    ids UUID[];
BEGIN
    FOR changed IN
        SELECT organization_id, count(DISTINCT parcel_id) AS changed_count
          FROM relation_new_rows
         WHERE active = TRUE
         GROUP BY organization_id
    LOOP
        IF changed.changed_count >= 50000 THEN
            PERFORM rebuild_land_parcel_render_owners(changed.organization_id);
        ELSE
            SELECT array_agg(DISTINCT parcel_id) INTO ids FROM relation_new_rows
             WHERE organization_id = changed.organization_id AND active = TRUE;
            PERFORM refresh_land_parcel_render_owners(changed.organization_id, ids);
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION forestiq_relation_render_update_stmt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    changed RECORD;
    ids UUID[];
BEGIN
    FOR changed IN
        WITH changed_rows AS (
            SELECT n.organization_id, n.parcel_id
              FROM relation_new_rows n
              LEFT JOIN relation_old_rows o
                ON o.organization_id=n.organization_id AND o.owner_id=n.owner_id AND o.parcel_id=n.parcel_id
             WHERE o.owner_id IS NULL OR n.active IS DISTINCT FROM o.active
            UNION
            SELECT o.organization_id, o.parcel_id
              FROM relation_old_rows o
              LEFT JOIN relation_new_rows n
                ON n.organization_id=o.organization_id AND n.owner_id=o.owner_id AND n.parcel_id=o.parcel_id
             WHERE n.owner_id IS NULL OR n.active IS DISTINCT FROM o.active
        )
        SELECT organization_id, count(DISTINCT parcel_id) AS changed_count
          FROM changed_rows GROUP BY organization_id
    LOOP
        IF changed.changed_count >= 50000 THEN
            PERFORM rebuild_land_parcel_render_owners(changed.organization_id);
        ELSE
            WITH changed_rows AS (
                SELECT n.organization_id, n.parcel_id
                  FROM relation_new_rows n
                  LEFT JOIN relation_old_rows o
                    ON o.organization_id=n.organization_id AND o.owner_id=n.owner_id AND o.parcel_id=n.parcel_id
                 WHERE o.owner_id IS NULL OR n.active IS DISTINCT FROM o.active
                UNION
                SELECT o.organization_id, o.parcel_id
                  FROM relation_old_rows o
                  LEFT JOIN relation_new_rows n
                    ON n.organization_id=o.organization_id AND n.owner_id=o.owner_id AND n.parcel_id=o.parcel_id
                 WHERE n.owner_id IS NULL OR n.active IS DISTINCT FROM o.active
            )
            SELECT array_agg(DISTINCT parcel_id) INTO ids FROM changed_rows
             WHERE organization_id = changed.organization_id;
            IF ids IS NOT NULL THEN
                PERFORM refresh_land_parcel_render_owners(changed.organization_id, ids);
            END IF;
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION forestiq_relation_render_delete_stmt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    changed RECORD;
    ids UUID[];
BEGIN
    FOR changed IN
        SELECT organization_id, count(DISTINCT parcel_id) AS changed_count
          FROM relation_old_rows
         WHERE active = TRUE
         GROUP BY organization_id
    LOOP
        IF changed.changed_count >= 50000 THEN
            PERFORM rebuild_land_parcel_render_owners(changed.organization_id);
        ELSE
            SELECT array_agg(DISTINCT parcel_id) INTO ids FROM relation_old_rows
             WHERE organization_id = changed.organization_id AND active = TRUE;
            PERFORM refresh_land_parcel_render_owners(changed.organization_id, ids);
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION forestiq_owner_render_update_stmt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    changed RECORD;
    ids UUID[];
BEGIN
    FOR changed IN
        WITH changed_owners AS (
            SELECT n.organization_id, n.id
              FROM owner_new_rows n
              JOIN owner_old_rows o ON o.id = n.id
             WHERE n.status IS DISTINCT FROM o.status
                OR n.assigned_to_subject IS DISTINCT FROM o.assigned_to_subject
        ), changed_parcels AS (
            SELECT DISTINCT r.organization_id, r.parcel_id
              FROM owner_parcel_relation r
              JOIN changed_owners o
                ON o.organization_id = r.organization_id AND o.id = r.owner_id
             WHERE r.active = TRUE
        )
        SELECT organization_id, count(*) AS changed_count
          FROM changed_parcels GROUP BY organization_id
    LOOP
        IF changed.changed_count >= 50000 THEN
            PERFORM rebuild_land_parcel_render_owners(changed.organization_id);
        ELSE
            WITH changed_owners AS (
                SELECT n.organization_id, n.id
                  FROM owner_new_rows n
                  JOIN owner_old_rows o ON o.id = n.id
                 WHERE n.status IS DISTINCT FROM o.status
                    OR n.assigned_to_subject IS DISTINCT FROM o.assigned_to_subject
            )
            SELECT array_agg(DISTINCT r.parcel_id) INTO ids
              FROM owner_parcel_relation r
              JOIN changed_owners o
                ON o.organization_id = r.organization_id AND o.id = r.owner_id
             WHERE r.organization_id = changed.organization_id AND r.active = TRUE;
            IF ids IS NOT NULL THEN
                PERFORM refresh_land_parcel_render_owners(changed.organization_id, ids);
            END IF;
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_land_parcel_render_insert
AFTER INSERT ON land_parcel
REFERENCING NEW TABLE AS parcel_new_rows
FOR EACH STATEMENT EXECUTE FUNCTION forestiq_land_parcel_render_insert_stmt();

CREATE TRIGGER trg_land_parcel_render_update
AFTER UPDATE ON land_parcel
REFERENCING OLD TABLE AS parcel_old_rows NEW TABLE AS parcel_new_rows
FOR EACH STATEMENT EXECUTE FUNCTION forestiq_land_parcel_render_update_stmt();

CREATE TRIGGER trg_land_parcel_render_delete
AFTER DELETE ON land_parcel
REFERENCING OLD TABLE AS parcel_old_rows
FOR EACH STATEMENT EXECUTE FUNCTION forestiq_land_parcel_render_delete_stmt();

CREATE TRIGGER trg_owner_parcel_relation_render_insert
AFTER INSERT ON owner_parcel_relation
REFERENCING NEW TABLE AS relation_new_rows
FOR EACH STATEMENT EXECUTE FUNCTION forestiq_relation_render_insert_stmt();

CREATE TRIGGER trg_owner_parcel_relation_render_update
AFTER UPDATE ON owner_parcel_relation
REFERENCING OLD TABLE AS relation_old_rows NEW TABLE AS relation_new_rows
FOR EACH STATEMENT EXECUTE FUNCTION forestiq_relation_render_update_stmt();

CREATE TRIGGER trg_owner_parcel_relation_render_delete
AFTER DELETE ON owner_parcel_relation
REFERENCING OLD TABLE AS relation_old_rows
FOR EACH STATEMENT EXECUTE FUNCTION forestiq_relation_render_delete_stmt();

CREATE TRIGGER trg_owner_render_update
AFTER UPDATE ON owner
REFERENCING OLD TABLE AS owner_old_rows NEW TABLE AS owner_new_rows
FOR EACH STATEMENT EXECUTE FUNCTION forestiq_owner_render_update_stmt();

-- Initial set-based backfill. The model is disposable and can always be rebuilt
-- later with SELECT rebuild_land_parcel_render(<organization_uuid>).
DO $$
DECLARE
    org RECORD;
BEGIN
    FOR org IN
        SELECT DISTINCT organization_id
          FROM land_parcel
         WHERE boundary IS NOT NULL AND NOT ST_IsEmpty(boundary)
    LOOP
        PERFORM rebuild_land_parcel_render(org.organization_id);
    END LOOP;
END
$$;

ANALYZE land_parcel_render;
ANALYZE land_parcel_render_owner;
