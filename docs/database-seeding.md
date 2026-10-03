# Database seeding policy

Production Flyway migrations define schema/reference data only. Demo CRM records must be loaded explicitly outside Flyway.

## Why V1 is not edited

`V1__core_crm_and_geospatial_schema.sql` historically inserted the `ForestIQ Demo` organization and two fake owners/parcels. That migration may already be applied in existing databases. Editing it would change its Flyway checksum and break normal upgrade validation.

`R__remove_legacy_demo_seed.sql` therefore performs an upgrade-safe cleanup instead. It deletes the legacy demo organization only when the database still matches the exact untouched seed shape: the known demo organization, exactly two known demo owners and exactly two known demo parcels. If an environment contains additional or modified data under that organization, the cleanup intentionally does nothing and the data must be reviewed manually.

A fresh database still executes historical V1, then the repeatable cleanup removes the untouched legacy seed before application use. The final migrated production database therefore contains no demo organization or fake CRM rows.

## Local development seed

Development sample data lives in `scripts/dev-seed.sql` and is never executed automatically by Flyway.

Example with Compose:

```sh
docker compose exec -T postgres sh -ec 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < scripts/dev-seed.sql
```

The local seed uses a separate fixed organization id and `.example.test` addresses. It is idempotent.

## E2E seed

Playwright uses deterministic test data from `scripts/e2e-seed-all.sql`. The wrapper creates/renames the dedicated E2E organization explicitly and then loads `scripts/e2e-seed.sql`.

CI must run the wrapper, not the inner file directly:

```sh
docker compose --env-file .env.ci exec -T postgres sh -ec 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < scripts/e2e-seed-all.sql
```

## Existing environment cleanup

Do not manually delete the historical demo organization solely by id. First verify whether users have added or modified owners, parcels, activities or workflow data under it. The repeatable migration deliberately refuses automatic deletion once the original demo shape has changed. If that happens, migrate any legitimate records to the correct organization or remove the obsolete data through an explicitly reviewed maintenance operation.
