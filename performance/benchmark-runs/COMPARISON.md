# Local before/after benchmark — 780k parcel dataset

**Host:** Windows 10 workstation, Docker Compose performance profile  
**Dataset:** 780,385 parcels, 549,121 owners, 1,151,702 relations, DB ~8.1 GB  
**Workload:** 200 requests @ concurrency 10 (tiles: 120 @ concurrency 5)

| Commit | Label | Directory |
| --- | --- | --- |
| `f4ca2a02975c` | before | `before-f4ca2a0-2026-08-28/` |
| `8819b7bcf9fc` | after (perf release) | `after-8819b7b-2026-08-29/` |
| `ad66c00cd91f` | after (parcel summary fix) | `after-ad66c0-2026-08-31/` |
| `08cefd6ce161` | after (legacy retirement + isolated benchmark) | `after-08cefd6-2026-08-31/` |

## Latest run (`08cefd6`) — supported paths only, p95 latency

| Endpoint | ok/total | p95 |
| --- | ---: | ---: |
| owners_cursor | 200/200 | 365 ms |
| relations_cursor | 200/200 | 366 ms |
| parcel_summary | 200/200 | 292 ms |
| tile_detail_warm | 120/120 | 296 ms |
| tile_detail_cold | 120/120 | 644 ms |
| tile_overview_warm | 120/120 | 334 ms |
| tile_overview_cold | 120/120 | 2,065 ms |

Legacy list endpoints are retired and excluded from the default benchmark harness.

## Progression

| Endpoint | Before (`f4ca2a0`) | After fix (`ad66c0`) | Isolated harness (`08cefd6`) |
| --- | --- | --- | --- |
| owners_cursor | 0/200 | 200/200, p95 275 ms | 200/200, p95 365 ms |
| relations_cursor | 0/200 | 200/200, p95 480 ms | 200/200, p95 366 ms |
| parcel_summary | 0/200 (500) | **200/200, p95 286 ms** | 200/200, p95 292 ms |
| tile warm (detail) | p95 2,112 ms | p95 237 ms | p95 296 ms |
| tile overview warm | — | — | p95 334 ms |

## Findings

- **Cursor/current read paths** are stable on the full local dataset after the perf release and parcel-summary fix.
- **Default benchmark** now measures only supported interactive paths and records workload-specific DB/resource evidence.
- **Overview tiles** remain the slowest cold path (~2 s p95 locally); warm overview cache stays sub-350 ms p95.

## How to reproduce

```bash
BENCHMARK_LABEL=after bash scripts/performance-local-benchmark.sh
```

Raw artifacts: `summary.tsv`, `dataset.txt`, `query-plans.txt`, `database-after.txt`, `database-delta.tsv` in each run directory.
