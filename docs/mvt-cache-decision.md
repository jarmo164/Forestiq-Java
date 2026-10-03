# Protected MVT cache decision

**Status:** Accepted for the current deployment model  
**Issue:** #138  
**Decision:** keep the bounded per-instance Caffeine MVT cache; do **not** add Redis/Valkey to protected tile delivery now.

## Context

ForestIQ currently has one API instance in the deployment profile being optimized. Protected MVT responses vary by:

- organization;
- assignment/subject visibility scope;
- layer/representation;
- z/x/y;
- data/representation version.

The in-process cache already includes all of those values in `TileCacheKey`, is bounded to approximately 256 MB, expires entries after 60 seconds and coalesces concurrent same-key misses through Caffeine's atomic loader.

The 780k-parcel full-dataset run showed that the main map problem was **not** a lack of shared cache:

- old overview cold SQL averaged ~1.51 seconds per generated tile;
- z12 detail generation averaged ~351 ms;
- supported read workload created zero new temp files/bytes;
- historical warm HTTP p95 was ~296–334 ms, but that benchmark did not prime the fixed cache key before timing;
- one API replica means Redis cannot currently eliminate cross-replica duplicate generation because there are no peer API replicas.

V34 therefore attacks the measured problem directly by preaggregating low-zoom overview cells. `scripts/performance-final-gates.sh` separately measures true warm hits after explicit cache priming.

## Decision

Keep Caffeine as a **disposable acceleration layer only**:

```text
request
  -> authenticate / resolve organization + visibility scope
  -> Caffeine scoped key
      -> hit: return protected bytes
      -> miss: one single-flight loader
          -> PostgreSQL/PostGIS read model
```

Redis/Valkey is not part of MVT correctness, authorization or invalidation. PostgreSQL/read-model state remains authoritative.

### Cache-key invariant

The protected tile cache key remains:

```text
(layer, organizationId, visibilityScope, z, x, y, dataVersion)
```

No lookup is allowed before authenticated organization/visibility resolution. Two organizations or two different assignment scopes can never share one protected tile key.

### Failure invariant

Evicting or losing the whole Caffeine cache must affect only latency, never correctness. A miss regenerates the same authorization-scoped representation from PostgreSQL.

## Why not Redis now

Adding a shared cache now would introduce:

- an extra network hop on every hit;
- serialization/storage overhead for potentially large MVT values;
- another production dependency and failure mode;
- invalidation/version coordination;
- security-sensitive shared keyspace;
- no demonstrated cross-replica benefit in the current single-replica deployment.

It would also obscure the actual cold-path problem measured in #137: runtime spatial aggregation.

## Reopen criteria

Re-evaluate a shared cache only when **all** of the following are true:

1. production runs two or more API replicas for a sustained period;
2. metrics show meaningful duplicate generation of the same protected key across replicas;
3. true-warm local Caffeine hits are already comfortably inside the HTTP SLO;
4. cold read-model generation has already been optimized and remains expensive enough that cross-replica sharing matters;
5. a two-scope security test proves organization/visibility isolation for the shared key implementation;
6. Redis/Valkey outage fallback has a bounded DB-load plan.

A useful trigger is duplicate cross-replica MVT generation consuming at least ~10% of map-generation CPU/DB time during normal production traffic. Until that evidence exists, shared MVT caching is intentionally **not planned**.

## Metrics to retain

Keep these low-cardinality metrics:

- `forestiq.map.tile.cache.requests{layer,result=hit|miss|coalesced}`
- `forestiq.map.tile.cache.loads{layer,result=success|failure}`
- `forestiq.map.tile.cache.entries`
- `forestiq.map.tile.latency{layer,zoom}`
- `forestiq.map.tile.bytes{layer,zoom}`
- `forestiq.map.tile.features{layer,zoom}`
- `forestiq.map.tile.feature_cap_reached{layer,zoom}`

Do not add organization IDs, subject IDs, cadastral codes or tile coordinates as metric labels.

## Consequence

#138 is resolved as an architecture decision rather than a Redis implementation task. If the deployment topology changes, open a new implementation issue with multi-replica measurements rather than silently enabling a distributed cache.
