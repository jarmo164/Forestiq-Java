-- When the generation importer adopts a row created by the old id-based legacy
-- importer, identity metadata is added before the set-based UPSERT. Do not let
-- that metadata-only adoption make the row look content-identical: force the
-- first generation publish to reconcile every normalized field.
CREATE OR REPLACE FUNCTION forestiq_notification_adoption_reconcile()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.source_name IS NULL
       AND NEW.source_name IS NOT NULL
       AND NEW.source_external_id IS NOT NULL THEN
        NEW.source_hash := NULL;
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_notification_adoption_reconcile ON cadastre_notification;
CREATE TRIGGER trg_notification_adoption_reconcile
BEFORE UPDATE OF source_name, source_external_id, source_hash ON cadastre_notification
FOR EACH ROW
EXECUTE FUNCTION forestiq_notification_adoption_reconcile();
