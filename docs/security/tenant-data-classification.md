# Tenant data classification and RLS rollout

## Security model

ForestIQ uses two independent tenant-isolation layers:

1. application queries keep explicit `organization_id` predicates;
2. PostgreSQL Row-Level Security (RLS) denies rows outside the active database tenant context.

The database context is stored in `app.organization_id`. The application DataSource overwrites that value every time a PostgreSQL connection is checked out. HTTP requests resolve it from the validated JWT `organization_id` claim. Background work must use `TenantDatabaseContext.runWithOrganization(...)` or `callWithOrganization(...)`.

A missing or invalid organization context intentionally resolves to no tenant rows.

## Runtime roles

- `forestiq_api`: API-owned CRM/business writes; no `BYPASSRLS`.
- `forestiq_ingest`: ingestion/source-owned writes; no `BYPASSRLS`.
- Flyway/migration identity: operationally separate from the API identity and allowed to perform schema migration. It must never be reused as the normal API runtime identity.

Production API credentials must not be PostgreSQL superuser credentials and must not have `BYPASSRLS`, because PostgreSQL superusers and BYPASSRLS roles bypass row security by design.

## Versioned inventory

Migration `V63__tenant_row_level_security.sql` creates `app.tenant_table_inventory`. It enumerates every table in the managed schemas that currently contains `organization_id` and derives its write owner from the database grants:

```sql
SELECT table_schema, table_name, api_writes, ingest_writes, write_owner
FROM app.tenant_table_inventory
ORDER BY table_schema, table_name;
```

Ownership values:

- `API`: API role has write ownership.
- `INGEST`: ingestion role has write ownership.
- `SHARED`: both roles can write and the boundary requires explicit review.
- `READ_ONLY`: neither runtime role has write ownership.

This view is deliberately schema-derived so a newly introduced `organization_id` table appears in the inventory without relying on a manually maintained list.

## First enforced RLS group

The first migration enables and forces RLS on:

| Table | Responsibility | Reason |
|---|---|---|
| `owner` | API | Core customer/owner record |
| `land_parcel` | API/read projection | Tenant-linked cadastral CRM record |
| `owner_parcel_relation` | ingest/API boundary | Canonical owner-to-parcel relationship |
| `owner_activity` | API | Owner lifecycle/history |
| `owner_parcel_relation_audit` | API | Relationship audit evidence |
| `deal_case` | API | Commercial workflow |
| `deal_case_event` | API | Deal audit/history |
| `contract` | API | Contract record |
| `contract_history` | API | Contract history |
| `saved_workbasket` | API | User decision-support workspace |
| `saved_workbasket_event` | API | Workbasket audit/history |

Each table uses the same policy:

```sql
USING (organization_id = app.current_organization_id())
WITH CHECK (organization_id = app.current_organization_id())
```

This means an accidental jOOQ query without an `organization_id` predicate still cannot read or mutate another organization's rows.

## Background jobs

Background jobs must not rely on ambient HTTP authentication. Scope each tenant unit of work explicitly:

```java
TenantDatabaseContext.runWithOrganization(organizationId, () -> {
    // repository/jOOQ work for exactly one organization
});
```

A job iterating multiple organizations must create a separate scope for each organization. Do not set one global tenant for a multi-tenant batch.

## Rollout checks

Before expanding RLS to another table group:

1. confirm the table appears in `app.tenant_table_inventory`;
2. confirm API/ingest ownership is correct;
3. add positive same-tenant and negative cross-tenant integration tests;
4. verify background jobs have explicit tenant scopes;
5. verify the runtime DB identity is not superuser and has no `BYPASSRLS`;
6. deploy the table group separately from unrelated refactors.

## Rollback

If a policy blocks legitimate production work, disable the affected table group without dropping data:

```sql
ALTER TABLE public.owner NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.owner DISABLE ROW LEVEL SECURITY;
```

Repeat only for the affected tables, restore the previous application version if required, and investigate the missing tenant scope before re-enabling RLS. Do not solve a tenant-context bug by granting `BYPASSRLS` to the API role.

The V63 migration itself is forward-only; production rollback is an explicit operational migration so the change remains auditable.
