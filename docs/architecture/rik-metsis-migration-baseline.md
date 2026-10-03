# RIK and MetsIS Integration Migration Baseline

Status: baseline accepted, cutover pending evidence

## Inventory

| Upstream call / flow | Current Java owner | Classification | Target disposition | Reason |
| --- | --- | --- | --- | --- |
| MetsIS / Forestek owner-cadastre portfolio snapshot | `MetsisPortfolioSyncService`, `OwnerCadastreIntegrationJob` | `ETL_ONLY` | Python adapter `OWNER_CADASTRE_RELATIONS` | Complete snapshot, staged and published asynchronously; provider credential belongs in ingest runtime only. |
| RIK Kinnistusraamat `objektide_arv` SOAP count | `ExternalRegistryService.fetchRikCadastreCount` | `REAL_TIME_DOMAIN_OPERATION` | Keep Java for now, documented exception | Request-time domain lookup for one owner/person; contracted SOAP account, latency/availability tied to API user action. Must not be scheduled ingestion. |
| Ametlikud Teadaanded inheritance notices | `InheritanceNoticeIngestionService`, `InheritanceNoticeIntegrationJob` | `ETL_ONLY` | Python migration tracked with owner/Weasel flows | Date-window catch-up ETL; watermark advances only after publish. |
| Weasel ownership transitions | `OwnershipTransitionService`, `WeaselTransitionIntegrationJob` | `ETL_ONLY` | Python migration tracked with owner/Weasel flows | Incremental paged feed; retry and cursor semantics must be shared runtime-owned. |

## Real-Time Exception: RIK objektide_arv

RIK remains Java-side only as a narrow real-time domain operation.

| Requirement | Decision |
| --- | --- |
| Use case | User/admin requests a current cadastre-object count for a specific owner/person or registry code. |
| Latency/availability | API request needs immediate answer or visible upstream failure; queued ingestion would not satisfy this interaction. |
| Fallback | If RIK is not configured or unavailable, API returns a domain error and does not enqueue background work. |
| Credential boundary | RIK SOAP credential remains API-bound while this exception exists; it must not be copied into Python ingest unless the flow is reclassified. |
| Scheduler | No scheduler branch may call this operation. |

## MetsIS Owner Portfolio Target

The Python adapter `MetsisOwnerPortfolioAdapter` owns acquisition and normalization. Staging uses temp-table `COPY` into `owner_portfolio_import_stage`; canonical publish must preserve:

- multi-owner parcel relations;
- source provenance;
- manual-vs-provider conflict handling;
- immutable relation audit;
- full snapshot safety so incomplete pages never become canonical state.

## Closure Evidence Needed

This issue can close only after every ETL_ONLY source has fixture/shadow parity, provider outage tests, malformed payload tests and idempotent retry evidence, followed by cutover routing and successful observed runs.


## Machine-readable disposition contract

The same source decisions are versioned in `ingest/source-dispositions.json`.
`load_source_dispositions(...)` validates the architecture constraints in tests:

- every `ETL_ONLY` source has a Python target job key;
- ETL credentials are ingest-owned;
- ETL work is not allowed to become an API scheduler path;
- a `REAL_TIME_DOMAIN_OPERATION` has a named Java owner, no Python job key, and
  cannot be scheduled;
- a retired source cannot retain credentials.

The current RIK `objektide_arv` call therefore remains an explicit API-owned
real-time exception, while MetsIS owner portfolio, Weasel ownership and inheritance
notices are explicit Python ETL targets.


## Cutover evidence CLI

Build the #361 `rik-metsis.json` entry with:

~~~bash
python scripts/build_rik_metsis_cutover_evidence.py rik-metsis-input.json \
  --repository-sha "$(git rev-parse HEAD)" \
  --output rik-metsis.json \
  --require-ready
~~~

Every ETL source must prove parity, provider-failure handling, rollback safety and at least two Python-owned runs. The RIK `objektide_arv` Java exception must remain explicitly authorization-bound, API-credential-owned and unavailable to schedulers. Missing proof fails closed.
