# ADR-002 — Canonical CRS, geometry and source-generation contract

**Status:** Proposed for implementation  
**Date:** 2026-09-06  
**Authority:** Epic #225, Feature #226, Issue #235  
**Depends on:** ADR-001  
**Related:** #228, #229, #231, #232, #240, #241, #244, #251

## 1. Purpose

ForestIQ must have one explicit contract for coordinate reference systems, geometry validity, parcel identity and published source generations. Without this contract, ingestion, spatial analysis, map rendering and browser coordinates can all be individually valid while still disagreeing with each other.

This ADR replaces the implicit assumption that one geometry column in EPSG:4326 should simultaneously serve provider fidelity, Estonia-centric analysis and web-map rendering.

## 2. CRS decision

### 2.1 Canonical source geometry

For Maa- ja Ruumiamet cadastre, ETAK and administrative vector datasets, the target canonical spatial representation is **L-EST97 / EPSG:3301**.

Reasons:

- it is the Estonia-centric projected CRS used by official spatial datasets and is suitable for local area/distance/intersection operations;
- it avoids performing core parcel analysis in angular longitude/latitude units;
- it keeps source normalization explicit rather than hiding projection assumptions in map code;
- web-map projection becomes a derived representation rather than the canonical truth.

If a source is delivered in another documented CRS, ingestion must explicitly normalize it into EPSG:3301 before publication.

### 2.2 Web-map geometry

The browser map representation uses **EPSG:3857** where required by vector/raster tile tooling.

EPSG:3857 geometry is always **derived and rebuildable** from the published canonical EPSG:3301 geometry. It must not become the only copy of source geometry.

### 2.3 Browser/API longitude-latitude values

When an API returns a geographic point, centroid or bbox intended for ordinary web clients, it uses **EPSG:4326 with explicit longitude/latitude order**:

```text
[longitude, latitude]
```

For Estonia, this means longitude is roughly in the 20s and latitude in the high 50s. Exact publication validation must use authoritative geographic bounds/administrative geometry rather than only these rough diagnostic ranges.

## 3. Boundary contract

| Boundary | CRS / order | Rule |
| --- | --- | --- |
| MaRu source artifact | provider-declared source CRS | Read CRS from authoritative source metadata; never guess from coordinate magnitude alone. |
| Staging geometry | source CRS may be retained transiently | Source CRS must be recorded with the run/layer schema metadata. |
| Published canonical cadastre/ETAK/admin geometry | EPSG:3301 | Spatial truth used for analysis and derivation. |
| Tile/read-model geometry | normally EPSG:3857 | Derived from current published canonical generation. |
| REST centroid/bounds for browser | EPSG:4326 `[lon, lat]` | Serialization contract is explicit and tested. |
| MapLibre raster/vector tile coordinates | tile engine/Web Mercator contract | Never manually swap axes in frontend code. |

## 4. `ST_SetSRID` rule

`ST_SetSRID` only labels coordinates whose CRS is already known. It **must never be used as a coordinate transformation**.

Correct conceptual sequence:

```sql
-- source coordinates are known to be in SOURCE_EPSG
ST_Transform(
  ST_SetSRID(source_geometry, SOURCE_EPSG),
  3301
)
```

An ingestion path that receives already typed geometry with a known SRID should preserve that SRID and use `ST_Transform` directly.

This rule specifically prevents a repeat of the legacy WFS axis/CRS class of failure documented in #168.

## 5. Geometry normalization

Before a source generation can be published, every required spatial row must pass the applicable checks.

### 5.1 Required checks

- source CRS is known and supported;
- geometry parses successfully;
- geometry is non-empty where the dataset contract requires geometry;
- geometry dimensionality is normalized to the supported 2D contract unless a layer explicitly requires Z/M;
- geometry type is compatible with the layer manifest;
- geometry is valid or is repaired only through a documented, measurable repair policy;
- transformed canonical geometry has SRID 3301;
- cadastral geometry passes Estonia publication-boundary validation;
- duplicate canonical source identities are rejected or resolved by an explicitly documented source rule.

### 5.2 `ST_MakeValid` policy

`ST_MakeValid` is not a silent blanket acceptance mechanism.

If repair is enabled for a dataset:

1. record the original geometry validation failure count;
2. repair in staging;
3. verify that the repaired geometry has the expected type/dimension and is not empty;
4. record repaired/rejected counters;
5. fail publication when repair/rejection exceeds a dataset-specific sanity threshold.

The raw source artifact/checksum and aggregate diagnostics remain available for investigation according to retention policy.

## 6. Estonia publication guard

A cadastral generation must fail closed when transformed geometry is clearly outside the expected Estonia spatial domain.

The preferred guard is based on an authoritative Estonia/administrative boundary dataset in the same projected CRS, with a documented tolerance for islands/coastal geometry. A coarse envelope check may be used as an early diagnostic, but it is not the only authoritative validation.

Tests must include:

- a known valid Estonian parcel polygon;
- a known provider-form sample transformed through the real ingestion path;
- an intentionally swapped-axis / incorrectly labelled sample;
- invalid and empty geometry;
- at least one boundary/coastal fixture so the guard is not over-restrictive.

## 7. Stable parcel identity

Source generations and ForestIQ business identity are separate concepts.

### 7.1 Domain identity

ForestIQ keeps a stable internal parcel identity for business references. The current cadastral code is the main authoritative external key for ordinary matching, but the internal identity is not recreated for every source generation.

A normal geometry or attribute refresh therefore behaves as:

```text
same stable parcel identity
+ new source facts / geometry generation
+ existing owner/CRM/deal history unchanged
```

### 7.2 Source changes

The reconciliation pipeline classifies at least:

- unchanged;
- attributes changed;
- geometry changed;
- added;
- removed;
- replacement/split/merge candidate where source evidence permits detection.

Removed/replaced parcels must not cause silent deletion of business history. They become explicit lifecycle/data-quality events handled by #241.

## 8. Source-generation model

A **source generation** is an immutable, validated snapshot of one external dataset/layer.

Minimum metadata:

```text
source key / provider
source dataset/layer
source date/version where available
artifact checksum
artifact size
schema fingerprint
source CRS
run id
row counts
validation counters
created/validated/published timestamps
status
```

### 8.1 Generation lifecycle

```text
DISCOVERED/DOWNLOADED
        |
        v
     STAGING
        |
        v
    VALIDATED
        |
        v
    PUBLISHED  <---- current pointer
        |
        v
     RETIRED
```

A failed run may have `FAILED` or `PARTIAL` run outcome, but **PARTIAL data is never publishable as a complete canonical generation** unless a specific dataset contract explicitly models partial publication as a separate product. Cadastre does not.

### 8.2 Atomic current pointer

Application readers resolve a dataset through one current published-generation pointer. Publication changes that pointer transactionally only after required validation and dependent publication prerequisites pass.

Readers never select `MAX(created_at)` or infer the current generation from newest staging data.

### 8.3 Last-known-good rollback

At least the immediately previous known-good generation remains addressable through the rollback window.

Rollback changes the published/current pointer and any coupled representation pointer; it does not require downloading the provider file again.

Cleanup of retired generations must respect the rollback window and operational retention policy.

## 9. Derived representation generation

Vector-tile/read-model data is a **derived representation** of a canonical source generation.

Minimum relationship:

```text
canonical source generation G42
  -> parcel read representation R88
  -> tile/cache representation version T88
```

Rules:

- R/T must record which canonical generation they derive from;
- derived geometry is rebuildable without contacting the provider;
- a representation for G42 cannot silently be used after G43 becomes current unless the cutover explicitly permits it;
- cache keys/URLs use a server-controlled representation generation, not an arbitrary caller-provided version;
- publication may require the derived representation to be ready before the source current pointer becomes visible to user-facing map reads.

The concrete tile engine remains a #243 decision.

## 10. Dataset grouping

Cadastre is published as one national logical generation.

ETAK/administrative layers normally publish independently so one failed layer does not roll back unrelated datasets. The #248 manifest may define an atomic group only when a concrete user-facing calculation requires layers to share one source-consistency boundary.

## 11. Organization scope

Public national source generations are organization-independent.

Do not store identical MaRu source geometry once per tenant. Organization/assignment access control applies when source data is joined to or represented through tenant-specific ForestIQ domain data.

This distinction must also be reflected in caches and tile generation.

## 12. Compatibility migration from current schema

The current application uses `land_parcel.boundary` as an EPSG:4326 geometry. The target migration must not rewrite that column in-place before all consumers are understood.

Preferred transition:

1. introduce generation-scoped canonical EPSG:3301 source storage;
2. reconcile source rows to stable parcel identities;
3. build compatibility projections/read models for existing consumers, including EPSG:4326 where necessary;
4. migrate spatial analysis to canonical 3301;
5. migrate map representation to generation-aware derived geometry;
6. remove or demote legacy geometry columns only after consumer inventory and cutover acceptance.

This permits a safe rollout without forcing owner/CRM/domain tables to be generation-scoped.

## 13. Publication invariants

A national cadastre generation may become current only when all mandatory invariants pass:

- artifact checksum/metadata recorded;
- expected file/schema can be opened;
- required layer exists;
- source CRS recognized;
- stable external identity present and unique according to source contract;
- row-count sanity checks pass;
- required geometry parses and validates;
- canonical geometry is EPSG:3301;
- Estonia guard passes;
- reconciliation completes without unexplained identity imbalance;
- required indexes/statistics are ready;
- required map/search representation for cutover is ready when publication policy requires it.

Any invariant failure leaves the previous generation current.

## 14. Testing contract

### Unit/fixture tests

- CRS metadata parsing/dispatch;
- source identity normalization;
- generation state transitions;
- source checksum/idempotency decisions.

### PostGIS integration tests

- source -> 3301 transform;
- valid/invalid/empty geometry;
- swapped-axis regression;
- area/intersection calculation in 3301;
- transactional current-generation switch;
- rollback;
- derived 3857 geometry and browser 4326 centroid/bounds.

### Migration tests

- existing stable parcel keeps its internal identity after geometry change;
- multi-owner relations are not duplicated;
- removed source parcel retains business history but is marked by explicit reconciliation outcome.

## 15. Operational observability

Expose low-cardinality metrics/counters for:

- source generation age;
- artifact bytes;
- source rows;
- valid/repaired/rejected geometry counts;
- import and validation duration;
- publish/rollback count;
- current generation age;
- derived representation build duration/failure.

Do not use cadastral code or parcel IDs as metric labels.

## 16. Consequences

### Benefits

- one authoritative spatial calculation CRS;
- no browser/ingestion axis ambiguity;
- safe rollback;
- stable business identity across national refreshes;
- tile engines can be replaced without changing canonical source data;
- source provenance and freshness are first-class.

### Costs

- temporary duplicate derived geometries during migration;
- explicit source/generation tables and reconciliation logic;
- source/representation publication coordination;
- additional storage for rollback generations.

## 17. Definition of done for #235

#235 can be closed when implementation/documentation demonstrates all of the following:

- every MaRu source has an explicit CRS/axis-order contract before ingestion;
- canonical cadastre/ETAK/admin geometry is defined as EPSG:3301;
- web-map and REST geographic representations are explicitly derived;
- publication fails closed on invalid/out-of-domain cadastral geometry;
- source generation and rollback semantics are persisted, not just described in application memory;
- a known swapped-axis fixture fails publication tests;
- derived map geometry is rebuildable from a published canonical generation without provider access.