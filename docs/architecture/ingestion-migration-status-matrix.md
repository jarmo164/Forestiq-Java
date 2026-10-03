# Ingestion Migration Status Matrix

This matrix tracks the migration from Spring-owned external ingestion to the Python
`ingest/` runtime. It is intentionally evidence-oriented: code can be ready before an
issue is closable when the issue's Definition of Done requires shadow runs, parity
reports, production smoke, or post-launch telemetry.

## Epic Status

| Issue | Scope | Current status | Close blocker |
| --- | --- | --- | --- |
| #355 | Separate external ingestion from the Spring API | Foundation implemented: Python runtime skeleton, DB role boundaries, shared queue/run contract, Spring enqueue-only routing, lifecycle docs, and source contracts are in `main`. | Full cutover/rollback evidence and removal of remaining source credentials from Spring after source parity. |
| #361 | Source-by-source migration to Python runtime | Adapter framework, watermarks, validation hooks, HTTP/WFS toolkit, Metsaregister foundation, MetsIS/owner, geospatial, Weasel, and inheritance adapter foundations are in `main`. | Every active source either cut over with parity evidence or explicitly documented as a Java real-time exception. |
| #367 | Productionise Python ingestion runtime | Runtime, healthcheck, queue worker, retry toolkit, and migration docs are in place. | Production-like smoke, recovery, metrics/logging evidence, and operational sign-off. |

## Source Migration Status

| Source / Job Area | Target classification | Python implementation status | Issue(s) | Remaining evidence |
| --- | --- | --- | --- | --- |
| Metsaregister WFS mirror | Python ETL | Adapter, staged COPY load path, shadow comparison helpers, and cutover runbook added. | #363 | Live shadow run, Java/Python parity report, outage/retry evidence, and two successful scheduled runs after routing `METSAREGISTER_WFS_MIRROR` to Python. |
| Cadastre / geospatial snapshots | Python ETL | Snapshot adapter, checksum change detection, EPSG:3301 bounds validation, staging load contract, and migration doc added. | #364 | Real snapshot import, parity counts/checksums against current Java flow, no-change rerun evidence, and controlled routing switch. |
| Owner / MetsIS portfolio snapshot | Python ETL | Owner-cadastre snapshot adapter and baseline docs added; RIK count remains a Java domain operation. | #372, #365 | Live MetsIS payload parity, retry/malformed-response evidence, and cutover proof. |
| Weasel ownership | Python ETL | Owner-domain source contract and Weasel adapter foundation added. | #365 | Shadow parity and cutover evidence for real Weasel payloads. |
| Inheritance notices | Python ETL | Owner-domain source contract and inheritance notice adapter foundation added. | #365 | Shadow parity and cutover evidence for real notice payloads. |
| RIK object count SOAP call | Java real-time exception | Documented as a synchronous domain operation, not a batch ingestion pipeline. | #372 | Keep exception explicit during #361 closure review. |

## Platform Work Already Landed

| Area | Files / behavior |
| --- | --- |
| Python runtime | `ingest/` package, Dockerfile, healthcheck, config, worker entrypoint, scheduler placeholder, baseline tests. |
| DB roles and boundaries | API and ingestion DB group roles, local login roles, Flyway credential separation, architecture docs. |
| Shared job/run contract | Queue leasing, heartbeat, stale run recovery, run state transitions, Python queue client, Java repository contract tests. |
| Spring enqueue-only routing | `forestiq.runtime.python-job-keys` / `FORESTIQ_RUNTIME_PYTHON_JOB_KEYS` controls route selected jobs to Python and keep Java workers from claiming them. |\n| Machine-readable runtime ownership | `config/ingestion-runtime-ownership.json` plus `scripts/validate_ingestion_runtime_ownership.py` fail closed if provider credentials/tooling leak back into the API or scheduler boundary. |
| Pipeline lifecycle | Extract, stage, validate, transform, load/prepare, publish lifecycle with idempotent publish handling. |
| Source adapter contract | Adapter registry, source metadata, watermarks, validation and structured source results. |
| HTTP/WFS toolkit | Retry/backoff, 429 handling, credential redaction, WFS pagination, page-limit protection, Metsaregister WFS adapter foundation. |

## Closure Rule

Do not close #355, #361, #363, #364, #365, #367, or #372 only because code has landed.
Close them after the linked source or runtime has the measured evidence named in the
issue: live shadow/parity where required, production-like smoke, rollback/cutover notes,
and any post-cutover scheduled-run proof.


## Runtime ownership guard

The source/runtime boundary is also machine-readable in `config/ingestion-runtime-ownership.json`.

`python scripts/validate_ingestion_runtime_ownership.py` verifies the current Render and production Compose layouts, API image boundary, Python ETL family inventory and the explicit RIK real-time Java exception. This guard is part of the ingestion fast test suite.

This does not replace the live parity/cutover evidence required to close #355; it prevents architecture drift while that evidence is collected.


## Source-by-source closure bundle

The cross-source closure contract for #361 is defined in `config/ingestion-cutover-requirements.json` and validated by `scripts/validate_ingestion_cutover_bundle.py`. Missing live evidence fails closed. Source-specific tools for #363/#364/#365 produce the sanitized files consumed by this bundle; #372 owns the RIK/MetsIS disposition evidence. #366 must not remove a legacy source until its bundle entry is ready.
