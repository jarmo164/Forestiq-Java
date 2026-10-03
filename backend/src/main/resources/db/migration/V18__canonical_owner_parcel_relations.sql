-- Canonical current ownership is represented by active owner_parcel_relation rows.
-- land_parcel.owner_id remains a deprecated compatibility projection only.

ALTER TABLE land_parcel
    ALTER COLUMN owner_id DROP NOT NULL;

COMMENT ON COLUMN land_parcel.owner_id IS
    'DEPRECATED compatibility projection. Current ownership is defined by active owner_parcel_relation rows and this column is maintained automatically.';

CREATE OR REPLACE FUNCTION sync_land_parcel_owner_projection(target_parcel_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    projected_owner_id uuid;
BEGIN
    SELECT opr.owner_id
      INTO projected_owner_id
      FROM owner_parcel_relation opr
     WHERE opr.parcel_id = target_parcel_id
       AND opr.active = TRUE
     ORDER BY opr.created_at ASC, opr.owner_id ASC
     LIMIT 1;

    UPDATE land_parcel
       SET owner_id = projected_owner_id
     WHERE id = target_parcel_id
       AND owner_id IS DISTINCT FROM projected_owner_id;
END;
$$;

CREATE OR REPLACE FUNCTION maintain_land_parcel_owner_projection()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM sync_land_parcel_owner_projection(OLD.parcel_id);
        RETURN OLD;
    END IF;

    PERFORM sync_land_parcel_owner_projection(NEW.parcel_id);

    IF TG_OP = 'UPDATE' AND OLD.parcel_id IS DISTINCT FROM NEW.parcel_id THEN
        PERFORM sync_land_parcel_owner_projection(OLD.parcel_id);
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_owner_parcel_relation_owner_projection ON owner_parcel_relation;
CREATE TRIGGER trg_owner_parcel_relation_owner_projection
AFTER INSERT OR UPDATE OF owner_id, parcel_id, active OR DELETE
ON owner_parcel_relation
FOR EACH ROW
EXECUTE FUNCTION maintain_land_parcel_owner_projection();

-- Reconcile all existing compatibility projections with the canonical active relations.
-- PostgreSQL does not allow the UPDATE target alias to be referenced from a
-- LATERAL item in the same UPDATE ... FROM clause, so compute the first active
-- owner for every parcel independently and join that projection back to p.
UPDATE land_parcel p
   SET owner_id = projected.owner_id
  FROM (
        SELECT DISTINCT ON (opr.parcel_id)
               opr.parcel_id,
               opr.owner_id
          FROM owner_parcel_relation opr
         WHERE opr.active = TRUE
         ORDER BY opr.parcel_id, opr.created_at ASC, opr.owner_id ASC
  ) projected
 WHERE projected.parcel_id = p.id
   AND p.owner_id IS DISTINCT FROM projected.owner_id;

UPDATE land_parcel p
   SET owner_id = NULL
 WHERE NOT EXISTS (
        SELECT 1
          FROM owner_parcel_relation opr
         WHERE opr.parcel_id = p.id
           AND opr.active = TRUE
 );

-- A registry source change is relevant independently to every active co-owner.
ALTER TABLE data_change_signal
    DROP CONSTRAINT IF EXISTS data_change_signal_source_unique;

ALTER TABLE data_change_signal
    ADD CONSTRAINT data_change_signal_source_unique
    UNIQUE (organization_id, owner_id, signal_type, source_registry, source_key);

-- The same Weasel transition may be relevant to multiple active co-owners.
ALTER TABLE ownership_transition_event
    DROP CONSTRAINT IF EXISTS ownership_transition_event_external_unique;

ALTER TABLE ownership_transition_event
    ADD CONSTRAINT ownership_transition_event_external_unique
    UNIQUE (organization_id, weasel_transition_id, owner_id);
