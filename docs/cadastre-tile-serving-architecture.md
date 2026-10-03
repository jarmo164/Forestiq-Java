# Cadastral tile-serving architecture

Status: Accepted for Epic #225 / tasks #243–#245.

## Decision

ForestIQ uses an **application-owned hybrid MVT architecture**.

- Spring Boot remains the authenticated tenant/assignment boundary and owns the bounded tile cache.
- PostGIS is the canonical tile engine for the currently published `MARU_CADASTRE` generation.
- `geospatial_source_current` is the only production selector for cadastral source generation. A client-supplied version must never select a generation or form the authoritative cache version.
- EPSG:3301 is canonical for stored cadastral geometry. Tile query bounds are transformed from Web Mercator to 3301 and candidate geometry is transformed to EPSG:3857 only for MVT encoding.
- Low zoom uses an explicit aggregate overview. It is not rendered as exact cadastral boundaries.
- Detail zoom uses exact published-generation geometry, joined to tenant `land_parcel` by cadastral code only to enforce business visibility and expose a stable domain parcel id.
- MVT contains no owner/contact/private business attributes. Domain details remain a separate authenticated REST read.
- `land_parcel_render` and its per-refresh geometry become transition assets, not canonical geometry. They can be removed once #242 cutover is complete.

## Alternatives considered

| Architecture | Advantages | Costs / rejection reason |
| --- | --- | --- |
| Spring + PostGIS `ST_AsMVT` (selected, hybrid overview/detail) | Reuses auth/tenant boundary; publication pointer is transactional; no extra service; on-demand cache naturally keyed by generation | DB tile work must be bounded and monitored; low zoom requires aggregate representation |
| Dedicated tile server over PostGIS | Strong tile specialization and independent scale | Adds a new auth/tenant boundary, deployment, observability and cache-coherency surface; not justified before measured PostGIS saturation |
| Fully pregenerated national tiles | Very fast public/static delivery | ForestIQ visibility varies by organization and assignment scope; pregeneration cardinality/rebuild cost grows multiplicatively and publication cutover becomes operationally heavier |
| Hybrid pre-generated public + protected overlay | Viable future option | Requires a product decision to expose a public cadastral layer separately. Keep as an escalation path if protected PostGIS tile metrics breach budgets. |

## Zoom contract and budgets

The budgets are **guardrails**, not fabricated benchmark results. CI/integration tests verify structural caps; production/staging telemetry supplies measured p50/p95.

| Band | Zoom | Representation | Feature budget | Response byte budget | Too-dense behavior |
| --- | ---: | --- | ---: | ---: | --- |
| Overview | 6–8 | fixed grid/count points | 4,096 hard cap | 256 KiB target | aggregate further; never emit exact boundaries |
| Transition | 9–10 | fixed grid/count points (no false boundary precision) | 4,096 hard cap | 384 KiB target | aggregate; UI communicates overview |
| Detail | 11–12 | exact current-generation parcel polygons | 5,000 hard cap | 768 KiB target | deterministic cap + metric; user zooms in |
| Detail-high | 13–22 | exact current-generation parcel polygons | 10,000 hard cap | 1 MiB target | deterministic cap + metric |

Exact cadastral labels start at z13. Tile extent remains 4096 with a small clip buffer.

## Cache contract

Authoritative key dimensions are:

`layer + organization + assignment-scope + z/x/y + published-generation-id + representation-version`

The source generation is resolved server-side for every request before cache lookup. Publication or rollback therefore changes the key immediately without an O(N) invalidation scan. A legacy client `?v=` parameter may be accepted temporarily for compatibility but is ignored for source selection/cache identity.

## Benchmark harness

The application already records:

- `forestiq.map.tile.latency` by layer/zoom (p50/p95 through the metrics backend),
- `forestiq.map.tile.bytes`,
- `forestiq.map.tile.features`,
- `forestiq.map.tile.cache.requests` hit/miss/coalesced,
- `forestiq.map.tile.feature_cap_reached`.

Run the following dataset classes for cold and warm cache passes:

1. Estonia-wide overview tiles at z6/z8.
2. Dense urban (Tallinn/Tartu) tiles at z10/z11/z13.
3. Sparse rural tiles at z10/z11/z13.
4. Empty/off-data and national-edge tiles.
5. The same tile before and after a generation publication and rollback.

For each class record p50/p95 application latency, Postgres statement execution/CPU from `pg_stat_statements`, encoded bytes, features, cache hit rate/evictions, and publication-to-first-new-tile time. Benchmark with unrestricted organization scope and an assignment-restricted scope because their query plans/cardinality differ.

### Escalation thresholds

Re-open the dedicated-tile-server alternative if, after indexes/query tuning and read-pool sizing, any representative detail band persistently has p95 > 500 ms, DB execution p95 > 300 ms, sustained cache hit rate < 70% on repeated navigation, or frequent byte/feature-cap breaches at normal working zooms.

## Operations and cost

Selected architecture adds no deployment unit. Its incremental cost is PostGIS read CPU plus bounded application memory (current tile cache limit 256 MiB per replica). A dedicated server adds a service, release/health/security boundary and likely another cache; full pregeneration adds object storage and rebuild workers. Those alternatives remain valid only when measured pressure pays for that complexity.

## Security

- Tile queries are tenant/assignment scoped in the backend.
- MVT properties are limited to stable parcel identifiers/cadastral code and non-sensitive display state needed by the map.
- Owner name, phone, contact information and assignment subject are never encoded into new generation-backed MVT.
- A click resolves the authorized domain summary/detail endpoint separately.

## Migration

1. #244 introduces generation-backed overview/detail reads and server-owned cache generation.
2. #245 switches MapLibre refresh/selection semantics to the generation contract.
3. #242 shadow-compares then cuts canonical geometry reads from legacy WFS/render tables to the published generation and disables nationwide WFS as a scheduled writer.
4. Legacy `land_parcel_render`/cache-version semantics are removed only after rollback has been exercised.
