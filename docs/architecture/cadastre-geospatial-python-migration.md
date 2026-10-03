# Cadastre and Geospatial Python Snapshot Migration

Status: implementation-ready, pending source parity evidence

## Implemented Foundation

The Python runtime has a reusable geospatial snapshot adapter foundation:

- deterministic file checksum for unchanged snapshot/no-op decisions;
- configurable source/provider/dataset metadata;
- EPSG:3301 Estonia envelope validation;
- source identity and hash normalization;
- temp-table `COPY` staging into `geospatial_feature`;
- generation-safe design that does not move current pointers until publish succeeds.

## Scope Mapping

| Source | Job key | Target |
| --- | --- | --- |
| Official cadastre snapshot/GPKG | `CADASTRE_PUBLIC_DATA` | Python snapshot adapter |
| ETAK/admin approved datasets | source-specific job keys | Same snapshot adapter pattern |
| Request-time map/detail reads | API/read model | Continue reading published canonical generations |

## Cutover Evidence Needed

This issue remains open until real source runs prove:

- official snapshot loads through Python runtime;
- failed/partial import leaves current parcel generation unchanged;
- unchanged checksum skips full rewrite;
- CRS/envelope validation catches bad geometry;
- shadow comparison covers parcel counts, stable IDs, bounds and samples;
- Java API/read models keep reading published canonical generations.


## Cutover evidence gate

Routing `CADASTRE_PUBLIC_DATA` or approved ETAK/admin datasets to Python now has a
machine-checkable evidence model in `geospatial_cutover.py`.

The cutover remains blocked unless all selected datasets prove:

- exact row-count parity with the current published generation;
- stable identity parity;
- geometry bounds parity;
- selected sample parity;
- a deliberately failed/partial import did not move the current generation;
- an unchanged snapshot checksum resulted in a no-op rather than a full rewrite;
- existing Java API/read-model behavior still works against the Python-published generation;
- at least two successful Python-owned runs were observed.

`require_cutover_ready(...)` fails closed on any missing proof. Use
`write_evidence(...)` to persist the sanitized result for the migration issue.
Raw provider payloads and credentials must not be attached as evidence.


## Evidence CLI

Build the #361 bundle entry from sanitized snapshot/parity measurements:

~~~bash
python scripts/build_geospatial_cutover_evidence.py geospatial-input.json \
  --repository-sha "$(git rev-parse HEAD)" \
  --output cadastre-geospatial.json \
  --require-ready
~~~

The command fails unless every dataset has exact row-count, stable identity, geometry-bounds and selected-sample parity; a deliberately failed import preserved the current generation; an unchanged checksum produced a no-op; Java read models remain compatible; and at least two Python-owned runs succeeded.

The generated `cadastre-geospatial.json` is directly consumable by the #361 source cutover bundle validator.
