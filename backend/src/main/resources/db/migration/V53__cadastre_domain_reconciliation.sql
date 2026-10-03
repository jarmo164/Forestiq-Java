-- V53: Reconcile immutable national cadastral generations with stable tenant/domain parcel ids.
--
-- geospatial_cadastre_parcel is the authoritative national source snapshot. land_parcel remains
-- the organization-scoped business identity referenced by owners, deals, evaluations and other
-- CRM state. A source refresh must therefore never recreate or silently reassign land_parcel ids.
--
-- Reconciliation is attached to geospatial_source_current itself. The trigger runs inside the
-- same transaction as publication/rollback, so a reconciliation failure aborts the pointer
-- change too. This also keeps generic control-plane rollback safe for cadastral data.

CREATE TABLE geospatial_cadastre_reconciliation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_key VARCHAR(128) NOT NULL DEFAULT 'MARU_CADASTRE'
        CHECK (source_key = 'MARU_CADASTRE'),
    previous_generation_id UUID,
    target_generation_id UUID NOT NULL,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('BASELINE', 'PUBLICATION', 'ROLLBACK')),
    previous_row_count BIGINT NOT NULL DEFAULT 0 CHECK (previous_row_count >= 0),
    target_row_count BIGINT NOT NULL DEFAULT 0 CHECK (target_row_count >= 0),
    stable_count BIGINT NOT NULL DEFAULT 0 CHECK (stable_count >= 0),
    added_count BIGINT NOT NULL DEFAULT 0 CHECK (added_count >= 0),
    removed_count BIGINT NOT NULL DEFAULT 0 CHECK (removed_count >= 0),
    attribute_changed_count BIGINT NOT NULL DEFAULT 0 CHECK (attribute_changed_count >= 0),
    geometry_changed_count BIGINT NOT NULL DEFAULT 0 CHECK (geometry_changed_count >= 0),
    attribute_and_geometry_changed_count BIGINT NOT NULL DEFAULT 0
        CHECK (attribute_and_geometry_changed_count >= 0),
    ambiguous_replacement_count BIGINT NOT NULL DEFAULT 0 CHECK (ambiguous_replacement_count >= 0),
    linked_domain_parcel_count BIGINT NOT NULL DEFAULT 0 CHECK (linked_domain_parcel_count >= 0),
    unresolved_domain_parcel_count BIGINT NOT NULL DEFAULT 0 CHECK (unresolved_domain_parcel_count >= 0),
    domain_link_failure_count BIGINT NOT NULL DEFAULT 0 CHECK (domain_link_failure_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX geospatial_cadastre_reconciliation_target_idx
    ON geospatial_cadastre_reconciliation (target_generation_id, created_at DESC);

CREATE TABLE geospatial_cadastre_domain_link (
    parcel_id UUID PRIMARY KEY REFERENCES land_parcel(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    source_key VARCHAR(128) NOT NULL DEFAULT 'MARU_CADASTRE'
        CHECK (source_key = 'MARU_CADASTRE'),
    cadastral_code VARCHAR(32) NOT NULL CHECK (btrim(cadastral_code) <> ''),
    first_seen_generation_id UUID,
    last_seen_generation_id UUID,
    reconciled_generation_id UUID NOT NULL,
    source_status VARCHAR(32) NOT NULL
        CHECK (source_status IN ('ACTIVE', 'REMOVED', 'REPLACEMENT_CANDIDATE', 'UNRESOLVED')),
    last_change_type VARCHAR(40) NOT NULL
        CHECK (last_change_type IN (
            'UNCHANGED', 'ADDED', 'REMOVED', 'ATTRIBUTE_CHANGED', 'GEOMETRY_CHANGED',
            'ATTRIBUTE_AND_GEOMETRY_CHANGED', 'REPLACEMENT_CANDIDATE', 'UNRESOLVED'
        )),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT geospatial_cadastre_domain_link_org_code_unique UNIQUE (organization_id, cadastral_code)
);
CREATE INDEX geospatial_cadastre_domain_link_generation_idx
    ON geospatial_cadastre_domain_link (reconciled_generation_id, source_status);
CREATE INDEX geospatial_cadastre_domain_link_status_idx
    ON geospatial_cadastre_domain_link (organization_id, source_status, cadastral_code);

-- Candidates are advisory only. No owner/business relation is ever reassigned from this table.
CREATE TABLE geospatial_cadastre_replacement_candidate (
    reconciliation_id UUID NOT NULL REFERENCES geospatial_cadastre_reconciliation(id) ON DELETE CASCADE,
    parcel_id UUID NOT NULL REFERENCES land_parcel(id) ON DELETE CASCADE,
    removed_cadastral_code VARCHAR(32) NOT NULL,
    candidate_cadastral_code VARCHAR(32) NOT NULL,
    overlap_old_ratio DOUBLE PRECISION NOT NULL CHECK (overlap_old_ratio >= 0 AND overlap_old_ratio <= 1.000001),
    overlap_new_ratio DOUBLE PRECISION NOT NULL CHECK (overlap_new_ratio >= 0 AND overlap_new_ratio <= 1.000001),
    candidate_rank INTEGER NOT NULL CHECK (candidate_rank BETWEEN 1 AND 3),
    PRIMARY KEY (reconciliation_id, parcel_id, candidate_cadastral_code)
);
CREATE INDEX geospatial_cadastre_replacement_candidate_parcel_idx
    ON geospatial_cadastre_replacement_candidate (parcel_id, reconciliation_id);

-- Append-only audit for affected business parcels. UNCHANGED rows are intentionally not expanded
-- into per-parcel audit events at national scale; aggregate stable_count is kept on the run.
CREATE TABLE geospatial_cadastre_domain_event (
    id BIGSERIAL PRIMARY KEY,
    reconciliation_id UUID NOT NULL REFERENCES geospatial_cadastre_reconciliation(id) ON DELETE CASCADE,
    parcel_id UUID NOT NULL REFERENCES land_parcel(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    cadastral_code VARCHAR(32) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX geospatial_cadastre_domain_event_parcel_idx
    ON geospatial_cadastre_domain_event (parcel_id, created_at DESC);

CREATE OR REPLACE FUNCTION reconcile_cadastre_domain_generation(
    previous_generation UUID,
    target_generation UUID,
    reconciliation_mode VARCHAR
)
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    v_reconciliation_id UUID := gen_random_uuid();
    previous_rows BIGINT := 0;
    target_rows BIGINT := 0;
    stable_rows BIGINT := 0;
    added_rows BIGINT := 0;
    removed_rows BIGINT := 0;
    attribute_changed_rows BIGINT := 0;
    geometry_changed_rows BIGINT := 0;
    both_changed_rows BIGINT := 0;
    ambiguous_replacements BIGINT := 0;
    linked_domain_rows BIGINT := 0;
    unresolved_domain_rows BIGINT := 0;
BEGIN
    IF target_generation IS NULL THEN
        RAISE EXCEPTION 'Cadastre reconciliation target generation is required';
    END IF;
    IF reconciliation_mode NOT IN ('BASELINE', 'PUBLICATION', 'ROLLBACK') THEN
        RAISE EXCEPTION 'Unsupported cadastre reconciliation mode: %', reconciliation_mode;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM geospatial_generation
         WHERE id = target_generation AND source_key = 'MARU_CADASTRE'
    ) THEN
        RAISE EXCEPTION 'Unknown MARU_CADASTRE target generation: %', target_generation;
    END IF;
    IF previous_generation IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM geospatial_generation
         WHERE id = previous_generation AND source_key = 'MARU_CADASTRE'
    ) THEN
        RAISE EXCEPTION 'Unknown MARU_CADASTRE previous generation: %', previous_generation;
    END IF;

    SELECT count(*) INTO target_rows
      FROM geospatial_cadastre_parcel
     WHERE generation_id = target_generation;
    IF previous_generation IS NOT NULL THEN
        SELECT count(*) INTO previous_rows
          FROM geospatial_cadastre_parcel
         WHERE generation_id = previous_generation;
    END IF;

    IF previous_generation IS NULL THEN
        added_rows := target_rows;
    ELSE
        WITH cur AS (
            SELECT cadastral_code,
                   source_attributes - 'eksport' - 'ogr_fid' AS attrs,
                   geom
              FROM geospatial_cadastre_parcel
             WHERE generation_id = target_generation
        ), old AS (
            SELECT cadastral_code,
                   source_attributes - 'eksport' - 'ogr_fid' AS attrs,
                   geom
              FROM geospatial_cadastre_parcel
             WHERE generation_id = previous_generation
        ), diff AS (
            SELECT cur.cadastral_code AS cur_code,
                   old.cadastral_code AS old_code,
                   cur.attrs AS cur_attrs,
                   old.attrs AS old_attrs,
                   cur.geom AS cur_geom,
                   old.geom AS old_geom
              FROM cur
              FULL OUTER JOIN old USING (cadastral_code)
        )
        SELECT count(*) FILTER (
                   WHERE cur_code IS NOT NULL AND old_code IS NOT NULL
                     AND cur_attrs IS NOT DISTINCT FROM old_attrs
                     AND ST_Equals(cur_geom, old_geom)
               )::bigint,
               count(*) FILTER (WHERE cur_code IS NOT NULL AND old_code IS NULL)::bigint,
               count(*) FILTER (WHERE cur_code IS NULL AND old_code IS NOT NULL)::bigint,
               count(*) FILTER (
                   WHERE cur_code IS NOT NULL AND old_code IS NOT NULL
                     AND cur_attrs IS DISTINCT FROM old_attrs
                     AND ST_Equals(cur_geom, old_geom)
               )::bigint,
               count(*) FILTER (
                   WHERE cur_code IS NOT NULL AND old_code IS NOT NULL
                     AND cur_attrs IS NOT DISTINCT FROM old_attrs
                     AND NOT ST_Equals(cur_geom, old_geom)
               )::bigint,
               count(*) FILTER (
                   WHERE cur_code IS NOT NULL AND old_code IS NOT NULL
                     AND cur_attrs IS DISTINCT FROM old_attrs
                     AND NOT ST_Equals(cur_geom, old_geom)
               )::bigint
          INTO stable_rows, added_rows, removed_rows, attribute_changed_rows,
               geometry_changed_rows, both_changed_rows
          FROM diff;
    END IF;

    IF previous_rows <> stable_rows + attribute_changed_rows + geometry_changed_rows
                        + both_changed_rows + removed_rows THEN
        RAISE EXCEPTION 'Cadastre reconciliation old-generation balance failed: old %, classified %',
            previous_rows,
            stable_rows + attribute_changed_rows + geometry_changed_rows + both_changed_rows + removed_rows;
    END IF;
    IF target_rows <> stable_rows + attribute_changed_rows + geometry_changed_rows
                      + both_changed_rows + added_rows THEN
        RAISE EXCEPTION 'Cadastre reconciliation target-generation balance failed: target %, classified %',
            target_rows,
            stable_rows + attribute_changed_rows + geometry_changed_rows + both_changed_rows + added_rows;
    END IF;

    INSERT INTO geospatial_cadastre_reconciliation (
        id, previous_generation_id, target_generation_id, mode,
        previous_row_count, target_row_count, stable_count, added_count, removed_count,
        attribute_changed_count, geometry_changed_count, attribute_and_geometry_changed_count
    ) VALUES (
        v_reconciliation_id, previous_generation, target_generation, reconciliation_mode,
        previous_rows, target_rows, stable_rows, added_rows, removed_rows,
        attribute_changed_rows, geometry_changed_rows, both_changed_rows
    );

    IF previous_generation IS NOT NULL THEN
        WITH old_removed AS (
            SELECT o.cadastral_code, o.geom
              FROM geospatial_cadastre_parcel o
             WHERE o.generation_id = previous_generation
               AND NOT EXISTS (
                   SELECT 1 FROM geospatial_cadastre_parcel n
                    WHERE n.generation_id = target_generation
                      AND n.cadastral_code = o.cadastral_code
               )
        ), new_added AS (
            SELECT n.cadastral_code, n.geom
              FROM geospatial_cadastre_parcel n
             WHERE n.generation_id = target_generation
               AND NOT EXISTS (
                   SELECT 1 FROM geospatial_cadastre_parcel o
                    WHERE o.generation_id = previous_generation
                      AND o.cadastral_code = n.cadastral_code
               )
        ), candidate_raw AS (
            SELECT p.id AS parcel_id,
                   o.cadastral_code AS removed_code,
                   n.cadastral_code AS candidate_code,
                   LEAST(1.0, x.intersection_area / NULLIF(ST_Area(o.geom), 0)) AS overlap_old,
                   LEAST(1.0, x.intersection_area / NULLIF(ST_Area(n.geom), 0)) AS overlap_new
              FROM land_parcel p
              JOIN old_removed o ON o.cadastral_code = p.cadastral_code
              JOIN new_added n ON o.geom && n.geom AND ST_Intersects(o.geom, n.geom)
              CROSS JOIN LATERAL (
                  SELECT ST_Area(ST_Intersection(o.geom, n.geom)) AS intersection_area
              ) x
             WHERE x.intersection_area > 0
        ), ranked AS (
            SELECT *,
                   row_number() OVER (
                       PARTITION BY parcel_id
                       ORDER BY GREATEST(overlap_old, overlap_new) DESC,
                                overlap_old DESC,
                                overlap_new DESC,
                                candidate_code ASC
                   ) AS rn
              FROM candidate_raw
             WHERE GREATEST(overlap_old, overlap_new) >= 0.50
        )
        INSERT INTO geospatial_cadastre_replacement_candidate (
            reconciliation_id, parcel_id, removed_cadastral_code, candidate_cadastral_code,
            overlap_old_ratio, overlap_new_ratio, candidate_rank
        )
        SELECT v_reconciliation_id, parcel_id, removed_code, candidate_code,
               overlap_old, overlap_new, rn::integer
          FROM ranked
         WHERE rn <= 3;

        SELECT count(*) INTO ambiguous_replacements
          FROM (
              SELECT parcel_id
                FROM geospatial_cadastre_replacement_candidate
               WHERE reconciliation_id = v_reconciliation_id
               GROUP BY parcel_id
              HAVING count(*) > 1
          ) ambiguous;
    END IF;

    WITH classified AS (
        SELECT p.id AS parcel_id,
               p.organization_id,
               p.cadastral_code,
               cur.cadastral_code IS NOT NULL AS in_target,
               old.cadastral_code IS NOT NULL AS in_previous,
               CASE
                   WHEN cur.cadastral_code IS NULL AND old.cadastral_code IS NULL THEN 'UNRESOLVED'
                   WHEN cur.cadastral_code IS NULL AND EXISTS (
                       SELECT 1
                         FROM geospatial_cadastre_replacement_candidate rc
                        WHERE rc.reconciliation_id = v_reconciliation_id
                          AND rc.parcel_id = p.id
                   ) THEN 'REPLACEMENT_CANDIDATE'
                   WHEN cur.cadastral_code IS NULL THEN 'REMOVED'
                   WHEN previous_generation IS NULL OR old.cadastral_code IS NULL THEN 'ADDED'
                   WHEN (cur.source_attributes - 'eksport' - 'ogr_fid') IS DISTINCT FROM
                        (old.source_attributes - 'eksport' - 'ogr_fid')
                        AND NOT ST_Equals(cur.geom, old.geom)
                       THEN 'ATTRIBUTE_AND_GEOMETRY_CHANGED'
                   WHEN (cur.source_attributes - 'eksport' - 'ogr_fid') IS DISTINCT FROM
                        (old.source_attributes - 'eksport' - 'ogr_fid')
                       THEN 'ATTRIBUTE_CHANGED'
                   WHEN NOT ST_Equals(cur.geom, old.geom) THEN 'GEOMETRY_CHANGED'
                   ELSE 'UNCHANGED'
               END AS change_type
          FROM land_parcel p
          LEFT JOIN geospatial_cadastre_parcel cur
            ON cur.generation_id = target_generation
           AND cur.cadastral_code = p.cadastral_code
          LEFT JOIN geospatial_cadastre_parcel old
            ON previous_generation IS NOT NULL
           AND old.generation_id = previous_generation
           AND old.cadastral_code = p.cadastral_code
    )
    INSERT INTO geospatial_cadastre_domain_link (
        parcel_id, organization_id, cadastral_code,
        first_seen_generation_id, last_seen_generation_id, reconciled_generation_id,
        source_status, last_change_type, updated_at
    )
    SELECT c.parcel_id,
           c.organization_id,
           c.cadastral_code,
           CASE WHEN c.in_target THEN target_generation ELSE NULL END,
           CASE
               WHEN c.in_target THEN target_generation
               WHEN c.in_previous THEN previous_generation
               ELSE NULL
           END,
           target_generation,
           CASE
               WHEN c.in_target THEN 'ACTIVE'
               WHEN c.change_type = 'REPLACEMENT_CANDIDATE' THEN 'REPLACEMENT_CANDIDATE'
               WHEN c.in_previous THEN 'REMOVED'
               ELSE 'UNRESOLVED'
           END,
           c.change_type,
           CURRENT_TIMESTAMP
      FROM classified c
    ON CONFLICT (parcel_id) DO UPDATE SET
        organization_id = EXCLUDED.organization_id,
        cadastral_code = EXCLUDED.cadastral_code,
        first_seen_generation_id = COALESCE(
            geospatial_cadastre_domain_link.first_seen_generation_id,
            EXCLUDED.first_seen_generation_id
        ),
        last_seen_generation_id = COALESCE(
            EXCLUDED.last_seen_generation_id,
            geospatial_cadastre_domain_link.last_seen_generation_id
        ),
        reconciled_generation_id = EXCLUDED.reconciled_generation_id,
        source_status = EXCLUDED.source_status,
        last_change_type = EXCLUDED.last_change_type,
        updated_at = CURRENT_TIMESTAMP;

    INSERT INTO geospatial_cadastre_domain_event (
        reconciliation_id, parcel_id, organization_id, cadastral_code, event_type, details
    )
    SELECT v_reconciliation_id,
           l.parcel_id,
           l.organization_id,
           l.cadastral_code,
           l.last_change_type,
           jsonb_build_object(
               'sourceStatus', l.source_status,
               'activeOwnerRelations', (
                   SELECT count(*)
                     FROM owner_parcel_relation opr
                    WHERE opr.parcel_id = l.parcel_id
                      AND opr.organization_id = l.organization_id
                      AND opr.active = TRUE
               ),
               'replacementCandidates', (
                   SELECT COALESCE(
                       jsonb_agg(
                           jsonb_build_object(
                               'cadastralCode', rc.candidate_cadastral_code,
                               'rank', rc.candidate_rank,
                               'overlapOld', rc.overlap_old_ratio,
                               'overlapNew', rc.overlap_new_ratio
                           ) ORDER BY rc.candidate_rank
                       ),
                       '[]'::jsonb
                   )
                     FROM geospatial_cadastre_replacement_candidate rc
                    WHERE rc.reconciliation_id = v_reconciliation_id
                      AND rc.parcel_id = l.parcel_id
               )
           )
      FROM geospatial_cadastre_domain_link l
     WHERE l.reconciled_generation_id = target_generation
       AND l.last_change_type <> 'UNCHANGED';

    SELECT count(*) FILTER (WHERE source_status <> 'UNRESOLVED')::bigint,
           count(*) FILTER (WHERE source_status = 'UNRESOLVED')::bigint
      INTO linked_domain_rows, unresolved_domain_rows
      FROM geospatial_cadastre_domain_link
     WHERE reconciled_generation_id = target_generation;

    UPDATE geospatial_cadastre_reconciliation
       SET ambiguous_replacement_count = ambiguous_replacements,
           linked_domain_parcel_count = linked_domain_rows,
           unresolved_domain_parcel_count = unresolved_domain_rows,
           domain_link_failure_count = 0
     WHERE id = v_reconciliation_id;

    RETURN v_reconciliation_id;
END;
$$;

CREATE OR REPLACE FUNCTION reconcile_cadastre_on_current_generation_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    previous_generation UUID;
    run_status VARCHAR(16);
    mode VARCHAR(16);
BEGIN
    IF NEW.source_key <> 'MARU_CADASTRE' THEN
        RETURN NEW;
    END IF;

    previous_generation := CASE WHEN TG_OP = 'UPDATE' THEN OLD.generation_id ELSE NULL END;
    IF previous_generation IS NOT DISTINCT FROM NEW.generation_id THEN
        RETURN NEW;
    END IF;

    SELECT r.status
      INTO run_status
      FROM geospatial_generation g
      JOIN geospatial_source_run r ON r.id = g.run_id
     WHERE g.id = NEW.generation_id
       AND g.source_key = 'MARU_CADASTRE';

    mode := CASE WHEN run_status = 'RUNNING' THEN 'PUBLICATION' ELSE 'ROLLBACK' END;
    PERFORM reconcile_cadastre_domain_generation(previous_generation, NEW.generation_id, mode);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_cadastre_reconcile_current_generation
AFTER INSERT OR UPDATE OF generation_id ON geospatial_source_current
FOR EACH ROW
EXECUTE FUNCTION reconcile_cadastre_on_current_generation_change();

DO $$
DECLARE
    current_generation UUID;
BEGIN
    SELECT generation_id INTO current_generation
      FROM geospatial_source_current
     WHERE source_key = 'MARU_CADASTRE';

    IF current_generation IS NOT NULL
       AND NOT EXISTS (
           SELECT 1
             FROM geospatial_cadastre_reconciliation
            WHERE target_generation_id = current_generation
              AND mode = 'BASELINE'
       ) THEN
        PERFORM reconcile_cadastre_domain_generation(NULL, current_generation, 'BASELINE');
    END IF;
END;
$$;