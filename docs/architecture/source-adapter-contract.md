# Source Adapter Contract

Status: accepted

Python source adapters implement one shared contract under `forestiq_ingest.sources`.

## Adapter Spec

Each adapter declares:

| Field | Meaning |
| --- | --- |
| `job_key` | Stable `IntegrationJobKey` routing key |
| `source_key` / `dataset_key` | Deterministic provider and dataset identity |
| `mode` | `SNAPSHOT` or `INCREMENTAL` |
| `pipeline_factory` | Pipeline implementation using the shared lifecycle |
| `config_keys` | Non-secret environment/config keys |
| `credential_keys` | Secret keys required only by worker runtime |
| `freshness_target` | Expected age target for Admin health |
| `retry_class` | Retryable, non-retryable or operator-action failure |
| `overlap_window` | Incremental overlap used to avoid ordering/clock gaps |

Adapters do not claim queue rows or own retry lifecycle. Queue claim, state transitions, counters and completion stay in the shared runtime.

## Snapshot Semantics

Snapshot adapters compute a source version and/or checksum before publication. If the last checkpoint has the same checksum/version, the run may complete as no-op without canonical rewrite. Watermark/checkpoint updates happen only after publish succeeds.

## Incremental Semantics

Incremental adapters read from `last_watermark - overlap_window`. Publication uses deterministic source keys and UPSERT/merge constraints, so overlap retries do not duplicate canonical data. The watermark advances only after successful publish.

## Registry

`SourceRegistry` maps `IntegrationJobKey` values to adapters and can be filtered by the same Python cutover job-key flag used by Spring.
