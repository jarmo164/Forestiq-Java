# Production ingestion verification

This runbook verifies the deployed ForestIQ scheduler/worker dataflow without storing production credentials in Git.

## When to run

Run after:

- a production deployment that changes scheduler/worker/runtime configuration;
- provider credential or organization-ID changes;
- a material ingestion/scheduling change;
- an incident involving stale or missing registry/ownership/inheritance data.

This is operational release evidence, not a permanently open development issue.

## Production topology expected by the verifier

The Render blueprint in `render.yaml` defines:

- `forestiq-api` as the HTTP API runtime;
- `forestiq-scheduler` as the dedicated scheduled-run producer;
- `forestiq-worker` as the durable integration queue consumer;
- persistent PostgreSQL as the durable queue/checkpoint/business-state store.

Provider credentials and organization IDs are Render-managed values and must not be committed.

## Verification command

Use a current production `ORG_ADMIN` bearer token. Never paste the token into GitHub issues, logs intended for sharing, or repository files.

```bash
export FORESTIQ_PRODUCTION_API_URL="https://forestiq-api.onrender.com"
export FORESTIQ_PRODUCTION_BEARER_TOKEN="<current production ORG_ADMIN token>"
python scripts/verify-production-ingestion.py
```

The verifier checks:

- `CADASTRE_PUBLIC_DATA`
- `FOREST_NOTIFICATIONS`
- `FOREST_PLAN`
- `OWNER_CADASTRE_RELATIONS`
- `WEASEL_OWNERSHIP_TRANSITIONS`
- `INHERITANCE_NOTICES`

## PASS criteria

A release/ops verification passes when:

- all required feeds report `configurationState=ENABLED`;
- health is `HEALTHY` or a deliberately understood `DEGRADED` state accepted by the verifier;
- at least the latest two completed `SCHEDULED` runs for every required feed are `SUCCEEDED`;
- the script exits with status 0 and prints `ForestIQ production ingestion verification: PASS`.

The two scheduled cycles prove that ingestion proceeds without browser/user traffic.

## Failure handling

A FAIL is production evidence and should create a new defect/incident only for the concrete failing condition. Typical checks:

1. confirm `forestiq-scheduler` and `forestiq-worker` are running the current `main` deployment;
2. confirm the relevant Render-managed provider URL/token/organization ID exists;
3. inspect the integration admin health/recent-run data for the failing job;
4. inspect worker/scheduler logs using job/run IDs, never secrets;
5. fix the specific runtime/provider/data defect and rerun the verifier.

Do not keep a generic GitHub issue open merely to wait for the next two scheduled cycles.

## Evidence retention

Safe evidence may include:

- verification date/time;
- deployed commit SHA;
- job names;
- `ENABLED` / health state;
- successful scheduled-run IDs/timestamps;
- final PASS/FAIL.

Never retain bearer tokens, provider access tokens/passwords, or other secrets in issue comments or committed benchmark files.
