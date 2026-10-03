# Cadastre migration — nationwide WFS to generation-safe snapshot ingestion

**Status:** Implementation plan  
**Date:** 2026-09-06  
**Authority:** Epic #225, Feature #226, Issue #236  
**Depends on:** ADR-001, ADR-002  
**Implementation issues:** #228, #239, #240, #241, #242, #243, #244, #245, #251

## 1. Objective

Replace the current nationwide Maa- ja Ruumiamet WFS paging path with an official downloadable cadastral snapshot ingestion path without breaking parcel identity, ownership links, CRM history, exact search or map behavior.

The migration is a **shadow -> validate -> cut over -> retire** process. It is not an in-place rewrite of the current geometry table.

## 2. Current-state inventory

### 2.1 Nationwide source ingestion

`backend/src/main/java/ee/forestiq/bootstrap/CadastreDatasetSyncService.java` currently:

- requests `kataster:ky_kehtiv` through WFS 2.0;
- pages with `count` + `startIndex`;
- requests GeoJSON in EPSG:4326;
- stores rows in `external_dataset_feature` under an `external_dataset_snapshot_run`;
- changes `external_dataset_current` when the row count matches;
- projects the current snapshot into existing tenant `land_parcel` rows;
- writes per-parcel `registry_sync_log` success rows;
- optionally deletes prior external snapshots.

This already contains useful staging/current-pointer ideas, but the source, geometry contract, tenant projection semantics and rollback policy do not match the new target architecture.

### 2.2 Current canonical/compatibility geometry

The current `land_parcel.boundary` path is EPSG:4326 and legacy WFS projection uses a pattern equivalent to:

```sql
ST_SetSRID(
  ST_Force2D(ST_MakeValid(ST_GeomFromGeoJSON(...))),
  4326
)
```

That existing path must not define the target geometry contract. ADR-002 makes canonical MaRu vector geometry EPSG:3301 and derived web representations explicit.

### 2.3 Existing map path

The application already has MapLibre/MVT behavior and a `land_parcel_render`-style read model from earlier performance work. These are valuable migration assets, but #243 decides the target tile implementation.

### 2.4 Business links that must survive

At minimum preserve:

- stable ForestIQ parcel identity;
- active/historical `owner_parcel_relation` semantics, including multi-owner parcels;
- owner marking/following/workflow state linked through existing domain identity;
- Metsaregister notification/plan/sub-part associations by parcel identity/cadastral code;
- evaluation/deal/contract references where parcel identity participates;
- admin/data-quality/audit history;
- exact cadastral search behavior and map focus.

A geometry refresh is never justification to recreate any of the above.

## 3. Target source path

```text
MaRu cadastral snapshot artifact
        |
        v
verified download + checksum
        |
        v
staging source generation
        |
        +--> schema/identity checks
        +--> CRS normalization to EPSG:3301
        +--> geometry/Estonia validation
        +--> row-count/checksum sanity
        |
        v
stable parcel reconciliation
        |
        v
published canonical source generation
        |
        +--> search projection
        +--> compatibility/domain projection
        +--> tile/read representation
```

Nationwide WFS is not part of the steady-state primary path.

## 4. Migration phases

## Phase 0 — freeze contracts and inventory consumers

**Issues:** #234, #235, #236  
**User-facing impact:** none

Tasks:

1. Accept ADR-001 and ADR-002 as the target contract.
2. Inventory all code and SQL that reads/writes:
   - `land_parcel.boundary`;
   - `external_dataset_*` cadastre rows;
   - `land_parcel_render` or equivalent map geometry;
   - per-parcel Maa-amet/WFS refresh APIs/actions;
   - exact cadastre search centroid/bounds;
   - registry sync freshness based on `MAAAMET_WFS` log rows.
3. Classify each consumer as:
   - **preserve unchanged**;
   - **temporary compatibility projection**;
   - **migrate to generation-aware source**;
   - **remove after cutover**.
4. Identify feature flags/config needed for shadow read comparison and emergency rollback.

Exit criteria:

- no unknown cadastral geometry write path remains;
- no domain ownership relation depends on a generation-specific parcel row identity;
- rollback can be described without data restoration from backup.

## Phase 1 — build new snapshot ingestion in shadow mode

**Issues:** #251, #239, #240  
**User-facing impact:** none; existing reads remain authoritative

### 4.1 Downloader

Implement #239 so the worker:

- downloads the official current snapshot to temporary/object storage;
- streams rather than buffering the full file in application memory;
- records source URL/config identity, observed source date/version when available, size and SHA-256 checksum;
- verifies free space and cleans incomplete downloads;
- detects an already published checksum and skips unnecessary full import unless explicitly forced.

### 4.2 Source/run/generation state

Generalize or replace current `external_dataset_*` semantics under #251.

The new model must distinguish:

```text
source
run
immutable generation
current published generation
previous/retired generation
```

Do not create one durable success audit record per parcel for a national snapshot.

### 4.3 Staging import

Import the provider artifact into a new staging generation using the bulk path selected by ADR-001.

Preferred implementation direction:

- standard GIS tooling such as GDAL/OGR where it reduces custom parsing risk;
- typed staging;
- PostgreSQL COPY/set-based operations where appropriate;
- explicit source CRS discovery/validation;
- canonical normalization to EPSG:3301.

The exact tool is replaceable; the generation/publication contract is not.

### 4.4 Shadow-only rule

During Phase 1:

- do not change current parcel/map/search reads;
- do not update owner relationships;
- do not publish the new generation as application current;
- do not make browser users depend on the new import job.

Exit criteria:

- one complete real national snapshot can be imported from a clean environment;
- repeated same-checksum import is idempotent;
- invalid/partial import leaves all existing application reads unchanged.

## Phase 2 — reconcile source generations to stable parcel identity

**Issue:** #241  
**User-facing impact:** none

Compare the new source generation with the current business parcel universe.

### 5.1 Required classification

For each external parcel identity classify:

- unchanged;
- attributes changed;
- geometry changed;
- added;
- removed;
- replacement/split/merge candidate when provider metadata enables that conclusion.

### 5.2 Stable identity rule

If cadastral identity remains stable, preserve the existing ForestIQ parcel ID and every downstream relation.

A geometry-only change may update source facts/derived representations, but must not create a second `owner_parcel_relation`.

### 5.3 Removed/replaced parcels

Do not hard-delete business history merely because a parcel disappeared from the new provider snapshot.

Produce a data-quality/lifecycle outcome that can be reviewed and handled by domain rules. Historical relations and audit data remain addressable.

### 5.4 Reconciliation report

Record aggregate counts for:

```text
old source count
new source count
stable identity matches
added
removed
attribute changes
geometry changes
unresolved replacement candidates
rejected source rows
```

A bounded diagnostic sample may be kept for investigation, but the audit model must not grow O(N) success rows.

Exit criteria:

- identity counts balance;
- stable identities prove ownership relations unchanged;
- multi-owner fixtures remain intact;
- unexplained identity loss is zero or explicitly approved before moving forward.

## Phase 3 — build and validate new search/map representations

**Issues:** #243, #244, #245  
**User-facing impact:** still shadow by default

### 6.1 Tile architecture decision

Run #243 before hardening the new source generation around the old MVT implementation.

At minimum compare credible variants for:

- latency and DB CPU;
- national/low-zoom behavior;
- detail accuracy;
- cache invalidation by generation;
- tenant/assignment authorization complexity;
- publication/rebuild duration;
- hosting/operations cost.

### 6.2 Derived representation

Build a representation tied explicitly to the new canonical source generation.

```text
source generation -> representation generation -> tile/cache version
```

No request-time provider calls are allowed for normal parcel tile rendering.

### 6.3 Exact search

Exact cadastral-code search must resolve from the new local generation and return browser-ready bounds/centroid in EPSG:4326 `[lon, lat]` while detailed source geometry remains canonical EPSG:3301.

### 6.4 Shadow comparison

For a deterministic fixture plus production-shaped sample, compare legacy and new paths:

- exact cadastral lookup finds the same stable parcel;
- map focus lands in Estonia and frames the parcel;
- representative polygon location/area is plausible;
- selected parcel opens the same owner/domain detail;
- low-zoom semantics are clearly bounded/generalized;
- working/detail zoom geometry is accurate;
- tenant/assignment visibility is unchanged.

Exit criteria:

- required map/search representation is ready for the same source generation that will be published;
- no critical correctness/security mismatch remains;
- benchmark targets are accepted.

## Phase 4 — controlled application cutover

**Issue:** #242  
**User-facing impact:** new source becomes authoritative

### 7.1 Cutover unit

Treat these as one coordinated release boundary:

- current canonical cadastral source generation;
- exact cadastral search generation;
- required parcel detail compatibility projection;
- required map representation generation.

Do not publish a source generation that leaves the user-facing map on unrelated stale geometry unless the release explicitly supports that temporary state and displays it safely.

### 7.2 Suggested cutover sequence

1. stop/disable nationwide WFS scheduler;
2. ensure no legacy WFS publication is in progress;
3. import and validate the cutover snapshot;
4. run stable parcel reconciliation;
5. build required search/map representation;
6. run pre-cutover comparison checks;
7. transactionally/atomically change current source and representation pointers/config;
8. invalidate only generation-keyed old caches naturally or by bounded version switch;
9. run smoke tests for exact search, map selection, owner relation and parcel detail;
10. monitor error/latency/freshness metrics.

### 7.3 Feature/config flags

During the stabilization window retain an operator-controlled ability to route cadastral **reads** back to the previous published generation/legacy compatibility path.

Do not keep two independent writers capable of silently overwriting the same canonical parcel geometry.

## Phase 5 — redefine refresh semantics

Legacy UI/admin concepts such as “refresh this parcel from Maa-amet WFS” must not remain the definition of cadastral truth.

Target semantics:

- **automatic**: daily (or source-appropriate) national snapshot check/import;
- **admin manual refresh**: trigger source check/import/revalidation through the shared source-run control plane;
- **parcel action**: show source generation/date and optionally re-query local canonical data; do not mutate canonical national geometry from an ad-hoc WFS response;
- **targeted WFS diagnostic**, if retained: read-only/diagnostic or isolated staging evidence; it cannot directly replace the published generation.

This supersedes the old per-parcel/full-refresh assumptions behind #97 while retaining its user expectation that a newly published generation appears on the map without a full page reload.

## Phase 6 — retire legacy WFS national path

After the stabilization window and successful rollback exercise:

- remove/disable scheduled nationwide WFS paging;
- remove obsolete WFS-specific current-source assumptions;
- stop writing misleading `MAAAMET_WFS` per-parcel success audit rows for snapshot publication;
- remove frontend coordinate/axis workaround code if any remains;
- remove compatibility geometry columns/read models only when their consumer inventory reaches zero;
- update operator runbooks and architecture documentation.

WFS CRS validation from #168 may remain in a bounded fallback/diagnostic adapter.

## 5. Validation gates

A production cutover is blocked if any of the following fails.

### Source completeness

- source artifact checksum recorded;
- required layer/schema recognized;
- row count is within accepted provider/sanity expectation;
- no unexplained duplicate/missing external identities.

### Spatial correctness

- canonical geometry is EPSG:3301;
- invalid/empty geometry is within policy and fully accounted for;
- Estonia publication guard passes;
- known swapped-axis fixture is rejected;
- representative areas/locations are plausible.

### Domain integrity

- stable parcel IDs are preserved for matching cadastral identities;
- owner–parcel active/historical relation counts do not change solely because of geometry refresh;
- multi-owner fixtures remain multi-owner;
- registry/evaluation/deal references still resolve.

### Map/search integrity

- exact search focuses correct parcel;
- tile selection returns same stable parcel ID;
- low-zoom representation does not pretend to show complete detailed boundaries;
- detail geometry appears at the approved working zoom;
- selected parcel detail agrees with search/tile identity.

### Operations

- current and previous known-good generations are addressable;
- publication/rollback is observable;
- API remains healthy if provider is unavailable after publication;
- new ingestion worker resource pressure is isolated from API pools.

## 6. Rollback plan

Rollback is a normal supported state transition, not a database restore procedure.

### 6.1 Rollback triggers

Rollback or abort cutover when any of these is observed:

- unexplained parcel identity loss/duplication;
- ownership/domain links no longer resolve;
- systematic geometry/CRS displacement;
- map/search identity mismatch;
- representation generation cannot be built reliably;
- authorization regression;
- severe latency/resource regression beyond accepted budget;
- source/schema drift that is not yet supported.

### 6.2 Rollback action

1. stop new generation publication;
2. set current canonical source pointer back to previous known-good generation;
3. restore coupled previous representation generation or rebuild it from the previous canonical generation;
4. route compatibility reads through the previous generation/path;
5. invalidate/advance server-controlled representation version as needed;
6. verify exact search, map focus and critical owner/parcel workflows;
7. record rollback run/outcome and reason.

Do **not**:

- reconstruct owner relationships from the provider snapshot;
- restore the whole database merely to undo one source generation;
- re-download the previous provider artifact if the retained generation is intact;
- let the failed generation become current for only some API replicas.

## 7. Relationship to older issues

| Issue | Migration disposition |
| --- | --- |
| #168 WFS CRS/axis-order | Keep safeguards only for retained WFS diagnostic/fallback path. Snapshot ingestion uses ADR-002 CRS contract. |
| #97 visible tile refresh | Replace per-refresh cache semantics with source/representation generation refresh under #245. User expectation remains. |
| #120 parcel render read model | Migration asset/candidate; retain, rebuild or replace based on #243. |
| #137 zoom-budget overview | Requirement remains; concrete implementation moves under #229/#243/#244. |
| #122 bulk COPY staging | Reuse principles where useful; GIS worker/tooling is free to use GDAL/OGR + COPY/set-based publication instead of being forced into the old Java implementation. |
| `docs/architecture.md` EPSG:4326 geometry statement | Compatibility description only after ADR-002; target canonical MaRu geometry is EPSG:3301. |
| `docs/backend-target-architecture.md` modular-monolith constraint | Not binding for #225 geospatial responsibilities; separate worker/tile/proxy responsibilities are allowed. |

## 8. Required implementation artifacts

Before #242 can close, the repository must contain:

- accepted ADR-001 and ADR-002;
- source/run/generation schema and state-transition tests;
- snapshot downloader with checksum/idempotency handling;
- real PostGIS generation-safe import integration tests;
- stable parcel reconciliation tests;
- tile architecture benchmark/decision record;
- exact search/map E2E tests against new generation;
- cutover + rollback operator runbook;
- explicit removal/retention decision for the old WFS scheduler and manual WFS actions.

## 9. Completion criteria for #236

Issue #236 is complete when this plan has been reviewed against the actual repository consumer inventory and every current cadastral path has one of four dispositions: preserve, compatibility-only, migrate or remove. Implementation of the cutover itself belongs to #242.