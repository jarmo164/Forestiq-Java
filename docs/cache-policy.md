# ForestIQ cache policy

**Scope:** issue #84 / epic #87 and protected MVT decision #138. PostgreSQL remains the source of truth. Redis/Valkey, Caffeine and HTTP caches are disposable optimizations only.

## Current rule

Do not add a distributed cache to a read path merely because Redis is available. A cache is introduced only after the uncached query is measured and the invalidation/security contract is explicit.

The protected MVT endpoint uses a bounded per-API-instance Caffeine cache. Production correctness must continue to hold when that cache is empty or evicted: PostgreSQL/PostGIS read models remain authoritative. The cache is limited to approximately 256 MB, expires entries after 60 seconds, includes organization + effective visibility scope + layer + z/x/y + data version in every key, and coalesces concurrent same-key misses through Caffeine's atomic loader. `docs/mvt-cache-decision.md` records why Redis/Valkey is not justified for protected MVT while the deployment has one API replica.

The reference-metadata cache remains the Redis-capable `owner-status-definitions` cache:

- PostgreSQL remains the source of truth;
- the cache key is `forestiq:v1:owner-status-definitions:org:<organization-id>`;
- values are serialized as JSON strings so Redis does not depend on Java object serialization;
- default Redis TTL is 5 minutes (`OWNER_STATUS_CACHE_TTL` can override it);
- status validation on write paths deliberately bypasses the cache and reads PostgreSQL;
- create/update/delete invalidates only the affected organization key;
- cache read/write/eviction failures are measured and fall back to PostgreSQL;
- `forestiq.cache.requests`, `forestiq.cache.errors`, `forestiq.cache.evictions` and `forestiq.cache.load` expose low-cardinality behavior;
- `OwnerStatusDefinitionCacheTest` proves cross-organization key separation and Redis/cache failure fallback.

## Allowed cache classes

| Class | Example | Default TTL | Scope requirement |
| --- | --- | ---: | --- |
| reference metadata | bounded lookup/status definitions | 5 min | organization when tenant-owned |
| dashboard/read summary | expensive bounded aggregate | 30–60 s | organization + all query filters |
| protected tile/result | organization parcel MVT | 60 s current Caffeine TTL | organization + caller/policy scope + z/x/y + representation version |
| public immutable-ish reference | non-sensitive registry metadata | 5–30 min | public namespace + version/source |

Huge WFS payloads, provider dumps, authorization decisions and mutable business aggregates are not cache candidates by default.

## Cache key contract

A protected cache key must be impossible to collide across authorization scopes. The canonical distributed form is:

```text
forestiq:<cache-version>:<resource>:org:<organization-id>:subject:<effective-subject-or-role-scope>:<normalized-query-hash>
```

The in-process MVT equivalent is the typed tuple `(layer, organizationId, visibilityScope, z, x, y, dataVersion)`.

Rules:

- `organizationId` always comes from authenticated `OrganizationContext`, never a request body/query override;
- include caller/assignment/policy scope whenever two users in the same organization can see different results;
- include every filter/sort/page/tile coordinate that changes the representation;
- include a cache schema/data version so contract changes can invalidate old entries without wildcard deletion;
- do not put access tokens, passwords or raw personal data in key names;
- use a bounded normalized hash for long filter sets.

Reference metadata that is identical for every authorized user inside one organization, such as owner status definitions, does not need a subject segment. The organization segment remains mandatory.

## Invalidation

Prefer short TTL + versioned keys over broad Redis scans.

Mutation policy:

- owner/assignment/status change invalidates or version-bumps owner/search/dashboard entries affected by that organization;
- owner status-definition create/update/delete evicts `owner-status-definitions` for that organization immediately;
- parcel geometry/ownership change refreshes the parcel render/overview read models and tile representations use a data-versioned cache key;
- completed registry import invalidates only the affected organization/source generation;
- permission/assignment changes must not wait for a long TTL when the cached representation is user-scoped.

Where precise invalidation is expensive, use an organization/source generation counter in the key and increment it transactionally/outbox-driven after a successful state change.

## Failure behavior

Cache failures are **fail-open to PostgreSQL for availability, fail-closed for authorization**:

1. authorization and organization scope are resolved before cache lookup;
2. a cache timeout/miss reads from PostgreSQL using the same scoped repository query;
3. a cache write failure does not fail a successful business read/write;
4. a malformed or scope-incompatible cached value is discarded, never returned optimistically;
5. Redis loss may increase DB load, so connection/query budgets and cache timeouts must be bounded;
6. complete Caffeine eviction/loss changes only latency and never visibility/correctness.

Default distributed-cache network timeout target should be well below the endpoint latency budget (tens of milliseconds, not seconds).

## Observability

Every enabled named cache must expose low-cardinality metrics:

- hit count/rate;
- miss count/rate;
- load duration;
- eviction count where supported;
- errors/timeouts;
- approximate entry/weight size where supported.

Protected MVT specifically exposes cache hit/miss/coalesced counts, load success/failure, entry count, generation latency, response bytes, encoded feature counts and cap hits.

Never label metrics with organization ID, subject ID, cadastral code, tile coordinate or arbitrary query text.

The reference cache uses only the bounded label `cache=owner-status-definitions` plus fixed result/operation values. Organization IDs remain only inside the cache key and are never metric labels.

## Size limits

- Do not cache unbounded lists.
- Do not cache raw provider pages/dumps as application cache entries.
- The MVT cache has a fixed weighted maximum (~256 MB) and short TTL; oversized representations must be fixed at the representation/read-model level rather than hidden behind more cache memory.
- Prefer many small bounded entries to a single organization-wide object graph.

## Security test invariant

For every protected cache introduced, add a two-scope test:

1. request org A / caller A and prime the cache;
2. request the same logical resource as org B or caller B;
3. prove the second request cannot receive the first representation;
4. repeat with cache loss/failure and prove the scoped PostgreSQL fallback is used.

`OwnerStatusDefinitionCacheTest#organizationIdIsPartOfCacheKey` and `#cacheReadFailureFallsBackToPostgreSqlLoader` implement this invariant for the reference cache. Map cache coverage additionally verifies scoped keys and coalesced concurrent misses. A future shared MVT cache must add an explicit multi-organization/multi-visibility integration test before it can be enabled.