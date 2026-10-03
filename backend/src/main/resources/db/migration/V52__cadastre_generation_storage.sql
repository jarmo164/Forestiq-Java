-- V52: generation-scoped canonical cadastral storage.
--
-- This table deliberately does not replace land_parcel.boundary yet. The old domain table
-- remains stable while #241 reconciles source generations to business parcel identities and
-- #242 cuts readers over from legacy nationwide WFS-derived geometry.

CREATE TABLE geospatial_cadastre_parcel (
    source_key VARCHAR(128) NOT NULL DEFAULT 'MARU_CADASTRE'
        CHECK (source_key = 'MARU_CADASTRE'),
    generation_id UUID NOT NULL,
    cadastral_code VARCHAR(32) NOT NULL CHECK (btrim(cadastral_code) <> ''),
    provider_feature_id VARCHAR(240),
    source_attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_area_m2 NUMERIC(18, 2) CHECK (source_area_m2 IS NULL OR source_area_m2 >= 0),
    geom geometry(MultiPolygon, 3301) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (generation_id, cadastral_code),
    CONSTRAINT geospatial_cadastre_parcel_generation_fk
        FOREIGN KEY (source_key, generation_id)
        REFERENCES geospatial_generation(source_key, id)
        ON DELETE CASCADE,
    CHECK (ST_SRID(geom) = 3301),
    CHECK (NOT ST_IsEmpty(geom))
);

-- Current-generation lookups usually constrain generation first, while direct cadastral-code
-- lookup benefits from a code-leading index during reconciliation and operational diagnostics.
CREATE INDEX geospatial_cadastre_parcel_code_generation_idx
    ON geospatial_cadastre_parcel (cadastral_code, generation_id);
CREATE INDEX geospatial_cadastre_parcel_geom_gist
    ON geospatial_cadastre_parcel USING GIST (geom);

-- Application readers that are migrated to the new source model must resolve through this view
-- (or the same explicit current-pointer join), never through MAX(created_at) or a staging run.
CREATE VIEW geospatial_cadastre_current AS
SELECT p.generation_id,
       p.cadastral_code,
       p.provider_feature_id,
       p.source_attributes,
       p.source_area_m2,
       p.geom,
       g.source_date,
       g.source_version,
       g.artifact_checksum,
       c.published_at
  FROM geospatial_source_current c
  JOIN geospatial_generation g
    ON g.source_key = c.source_key
   AND g.id = c.generation_id
   AND g.status = 'PUBLISHED'
  JOIN geospatial_cadastre_parcel p
    ON p.source_key = c.source_key
   AND p.generation_id = c.generation_id
 WHERE c.source_key = 'MARU_CADASTRE';

-- Generation rows become immutable once validation completes. Failed/staging data may be
-- deleted by cleanup and retired generations may be removed after the rollback retention window,
-- but no row content is allowed to change after STAGING.
CREATE OR REPLACE FUNCTION guard_geospatial_cadastre_generation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_generation UUID;
    target_status VARCHAR(16);
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_generation := OLD.generation_id;
    ELSE
        target_generation := NEW.generation_id;
    END IF;

    SELECT status
      INTO target_status
      FROM geospatial_generation
     WHERE id = target_generation;

    IF TG_OP IN ('INSERT', 'UPDATE') AND target_status <> 'STAGING' THEN
        RAISE EXCEPTION 'Cadastre generation % is immutable in status %',
            target_generation, target_status;
    END IF;

    IF TG_OP = 'DELETE' AND target_status IN ('VALIDATED', 'PUBLISHED') THEN
        RAISE EXCEPTION 'Cadastre generation % cannot be deleted in status %',
            target_generation, target_status;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_guard_geospatial_cadastre_generation_mutation
BEFORE INSERT OR UPDATE OR DELETE ON geospatial_cadastre_parcel
FOR EACH ROW
EXECUTE FUNCTION guard_geospatial_cadastre_generation_mutation();
