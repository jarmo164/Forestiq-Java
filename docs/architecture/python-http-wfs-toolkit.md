# Python HTTP and WFS Toolkit

Status: accepted

Python source adapters use `forestiq_ingest.http` for provider HTTP/WFS access.

## Retry Policy

| Failure | Behavior |
| --- | --- |
| Timeout/network/transport | Retry with bounded exponential backoff and jitter |
| HTTP `5xx`, `408`, `425` | Retry with bounded exponential backoff and jitter |
| HTTP `429` | Retry and honor `Retry-After` when valid |
| HTTP `401`, `403`, other terminal `4xx` | Fail closed without spending retry budget |
| Response body over limit | Terminal failure |

## WFS Pagination

`WfsClient.pages(...)` uses `count` and `startIndex`. Pagination stops only on:

- empty response body;
- explicit empty feature collection / `numberReturned=0`;
- adapter-provided cancellation.

If `max_pages` is reached first, the toolkit raises `PageLimitExceeded`. That makes truncation visible instead of silently publishing an incomplete snapshot.

## Observability and Redaction

Standard log context is low-cardinality and includes `run_id`, `job_key`, `source`, `batch_no`, `source_cursor`, `attempt`, `duration_ms` and `outcome`. Authorization, API key and cookie headers are redacted. Source payload bodies are never logged by the toolkit.

## Adapter Rule

Adapters provide endpoint, source schema, transformation and domain validation. They should not implement their own generic timeout, retry, rate-limit or WFS paging loops unless a source-specific exception is documented.
