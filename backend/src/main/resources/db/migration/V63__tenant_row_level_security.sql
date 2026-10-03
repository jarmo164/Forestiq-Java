-- V63: database-enforced tenant isolation for the highest-risk CRM tables.
--
-- API connections set app.organization_id when a connection is checked out.
-- The policy therefore remains effective even when a future jOOQ query forgets
-- to add an explicit organization_id predicate.
--
-- Flyway must use a dedicated migration identity in production. Superusers and
-- roles with BYPASSRLS bypass PostgreSQL RLS by design and must never be used as
-- the normal API runtime identity.

CREATE OR REPLACE FUNCTION app.current_organization_id()
RETURNS UUID
LANGUAGE sql
STABLE
AS $$
    SELECT CASE
        WHEN current_setting('app.organization_id', true)
             ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        THEN current_setting('app.organization_id', true)::uuid
        ELSE NULL
    END
$$;

GRANT EXECUTE ON FUNCTION app.current_organization_id() TO forestiq_api, forestiq_ingest;

-- Keep the inventory generated from the actual schema so new tenant tables are
-- visible immediately. Ownership is derived from the write grants established
-- in V61 and later migrations.
CREATE OR REPLACE VIEW app.tenant_table_inventory AS
SELECT
    c.table_schema,
    c.table_name,
    (has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'INSERT')
          OR has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'UPDATE')
          OR has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'DELETE')) AS api_writes,
    (has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'INSERT')
          OR has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'UPDATE')
          OR has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'DELETE')) AS ingest_writes,
    CASE
        WHEN (has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'INSERT')
          OR has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'UPDATE')
          OR has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'DELETE')) AND (has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'INSERT')
          OR has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'UPDATE')
          OR has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'DELETE')) THEN 'SHARED'
        WHEN (has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'INSERT')
          OR has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'UPDATE')
          OR has_table_privilege('forestiq_api', format('%I.%I', c.table_schema, c.table_name), 'DELETE')) THEN 'API'
        WHEN (has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'INSERT')
          OR has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'UPDATE')
          OR has_table_privilege('forestiq_ingest', format('%I.%I', c.table_schema, c.table_name), 'DELETE')) THEN 'INGEST'
        ELSE 'READ_ONLY'
    END AS write_owner
FROM information_schema.columns c
WHERE c.column_name = 'organization_id'
  AND c.table_schema IN ('public', 'core', 'app', 'ingest')
ORDER BY c.table_schema, c.table_name;

GRANT SELECT ON app.tenant_table_inventory TO forestiq_api, forestiq_ingest;

-- Highest-risk primary records and their closest audit/history records.
DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'owner',
        'land_parcel',
        'owner_parcel_relation',
        'owner_activity',
        'owner_parcel_relation_audit',
        'deal_case',
        'deal_case_event',
        'contract',
        'contract_history',
        'saved_workbasket',
        'saved_workbasket_event'
    ]
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('DROP POLICY IF EXISTS forestiq_tenant_isolation ON public.%I', table_name);
        EXECUTE format(
            'CREATE POLICY forestiq_tenant_isolation ON public.%I
             USING (organization_id = app.current_organization_id())
             WITH CHECK (organization_id = app.current_organization_id())',
            table_name
        );
    END LOOP;
END
$$;
