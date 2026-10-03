# ForestIQ performance/scale acceptance gates (2026-09-01)

Environment used: local `main` at commit `009e1bf5f45d2df47fd1249fc6e645477a38b650`

## Raw run references

- `.run/performance/final-clean`
- `.run/performance/v37-cold-overview-20260901-122623`
- `.run/performance-history/20260901-124408-009e1bf5f45d`

## Issue status table

| Issue | PASS/FAIL | mootetud toend | allesjaanud blocker |
|---|---|---|---|
| #113 | FAIL | `final-clean/summary.tsv`: `tile_overview_true_warm p95=19.591ms`, `tile_detail_true_warm p95=132.208ms`; `system.txt` | `dirty=3` (clean checkout gate ebaonnestus), detail true-warm ule 100ms |
| #137 | FAIL | `v37-cold-overview` run: `tile_overview_cold p95=251.568ms`, `tile_overview_warm p95=250.547ms`; `final-clean` true-warm overview 19.591ms | low-zoom overview p95 > 200ms; browser kontroll blokkeerus (`{\"detail\":\"Login service unavailable\"}` callbackis) |
| #125 | FAIL | `performance-ingestion-concurrency.sh` katse | sisendfail puudub: `andmed/teatis_arhiiv_full.normalized.csv` |
| #136 | FAIL | puudub usaldusvaarne ingest run | #125 gate FAIL + representative import tegemata |
| #132 | FAIL | `history-final` run olemas, `http-summary.tsv` kogutud | dataset mitte representative: `cadastre_notification=3`, `cadastre_sub_part=0` |
| #133 | FAIL | `history-final` observability + plans olemas | archive/partitioning otsust ei saa teha 8M/1.8M ilma |
| #138 | FAIL | 1 replica andmed olemas (`final-clean`), scale katse tehtud | `--scale api=2` nurjub: `Bind for 127.0.0.1:8080 failed` |
| #114 | FAIL | hinnatud #125/#136 pohjal | bulk ingestion epic acceptance ei saa sulgeda enne #125/#136 PASS |

## Key metric excerpts

### Final gate (`.run/performance/final-clean/summary.tsv`)

| benchmark | ok | p50_ms | p95_ms | p99_ms |
|---|---:|---:|---:|---:|
| health_no_auth | 120 | 23.288 | 77.744 | 2110.182 |
| owner_size1 | 120 | 27.276 | 75.665 | 2544.721 |
| tile_detail_true_warm | 120 | 59.403 | 132.208 | 2123.579 |
| tile_overview_true_warm | 120 | 9.158 | 19.591 | 2067.614 |

### Cold/warm overview supplement (`.run/performance/v37-cold-overview-20260901-122623/summary.tsv`)

| benchmark | ok | p50_ms | p95_ms | p99_ms |
|---|---:|---:|---:|---:|
| tile_overview_cold | 120 | 224.908 | 251.568 | 281.635 |
| tile_overview_warm | 120 | 221.129 | 250.547 | 389.850 |

### History-scale cardinality (`.run/performance-history/20260901-124408-009e1bf5f45d/database-observability.txt`)

| relation | rows |
|---|---:|
| cadastre_notification | 3 |
| cadastre_sub_part | 0 |
| forest_registry_feature | 13 |

### Multi-replica scale attempt

- Command: `docker compose -f compose.yaml -f compose.performance.yaml up -d --scale api=2`
- Result: `Error response from daemon: ... Bind for 127.0.0.1:8080 failed: port is already allocated`

## Notes

- No private payloads, tokens, cadastral codes, or `.env` data are included in this report.
- `gh` is not authenticated in this environment, so issue closing itself was not executed.
