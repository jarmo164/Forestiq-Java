# Python Ingestion Pipeline Lifecycle

Status: accepted

All Python source adapters use the shared lifecycle:

`EXTRACT -> STAGE -> VALIDATE -> TRANSFORM -> LOAD/PREPARE -> PUBLISH`

The framework lives in `forestiq_ingest.pipelines.base`. Validation helpers live in `forestiq_ingest.validation.rules`.

## Guarantees

- Current/last-known-good data remains readable while a new run stages data.
- Validation failure does not publish a new generation.
- Checkpoint/watermark advancement happens only after publish succeeds.
- Source adapters process extract chunks with a configurable `chunk_size`.
- Source-specific validation composes on top of shared checks for required fields, duplicates, row-count sanity, schema drift and geometry validity.
- Run state and counters are reported through the shared PostgreSQL queue contract.

## Metadata

Each publishable record carries:

| Field | Purpose |
| --- | --- |
| `source` | Provider/source key |
| `source_id` | Stable provider identity |
| `source_updated_at` | Provider update timestamp when available |
| `ingest_run_id` | Shared run identifier |
| `source_hash` | Change fingerprint |
| `published_generation_id` | Generation/version visible after publish |

## Adapter Rule

Adapters should stage provider payloads first, then publish with database-side set operations or COPY where data volume is large. Python row loops are acceptable for small validation/control records, but not for geospatial bulk publication.
