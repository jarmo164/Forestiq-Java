# Runtime SQL and generated jOOQ policy

ForestIQ treats Flyway migrations as the database schema source of truth and commits the generated jOOQ schema under `backend/src/generated/jooq`.

## Default rule

Application CRUD and ordinary query code must use generated tables and fields from `ee.forestiq.jooq.Tables`. Do not introduce `table("...")`, `field(name("..."))`, or equivalent string-based schema references when the table/column is represented by generated jOOQ code.

This applies especially to owner, deal, inheritance, registry metadata, reminder, activity and audit persistence. Schema changes should therefore become generation- or compile-time failures instead of runtime SQL surprises.

## CI guarantee

The backend CI job runs:

```sh
./gradlew generateJooq --no-daemon
git diff --exit-code -- src/generated/jooq
```

A Flyway migration that changes generated schema without committing the matching generated sources fails CI.

## Intentional raw SQL exceptions

Raw SQL is acceptable only when it expresses a database-specific operation substantially more clearly than the generated DSL. Current intentional exceptions are:

- PostGIS geometry/vector-tile expressions and spatial functions.
- PostgreSQL advisory locks used to guarantee a single registry refresh transaction across application instances.
- PostgreSQL regular-expression matching used by the inheritance background candidate query; the table/column operand still comes from generated jOOQ.
- Registry freshness dashboard queries that calculate several correlated latest-success timestamps from the immutable registry audit log in one bounded query.
- jOOQ code generation bootstrap SQL itself.

Every new exception should have a nearby code comment explaining why raw SQL is intentional. Prefer generated fields even inside templated jOOQ conditions so schema references continue to receive compile-time checking.

## Migration guidance

When touching an older runtime query:

1. Replace string table and column names with generated jOOQ tables/fields.
2. Use generated Java types (`OffsetDateTime`, `JSONB`, `UUID`, etc.) and convert at the application boundary where DTOs intentionally expose another type such as `Instant`.
3. Keep organization scoping explicit in every tenant-owned query.
4. Leave raw SQL only for one of the documented database-specific cases, and document any new category here.
5. Run `check` and `generateJooq`, then verify the generated-source diff is clean.
