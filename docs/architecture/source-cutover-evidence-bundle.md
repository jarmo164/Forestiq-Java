# Source-by-source cutover evidence bundle

Related: #361, #363, #364, #365, #366, #372.

ForestIQ now has one machine-readable closure gate for the source-by-source Python migration.

The requirements live in `config/ingestion-cutover-requirements.json`. Sanitized live evidence files are kept outside the repository until reviewed and can be validated with:

~~~bash
python scripts/validate_ingestion_cutover_bundle.py \
  --evidence-dir /path/to/sanitized-cutover-evidence
~~~

Required filenames:

- `metsaregister.json` — #363
- `cadastre-geospatial.json` — #364
- `owner-flows.json` — #365
- `rik-metsis.json` — #372

A Python ETL source is ready only when parity is explicitly ready, at least two successful Python-owned runs are recorded, rollback/recovery is verified and the evidence references the repository SHA.

The RIK/MetsIS family is intentionally `MIXED`: asynchronous ETL must become READY while a documented request-time Java operation may use `EXCEPTION` only with an explicit reason and authorization boundary.

The validator fails closed on missing files. This prevents #361/#366 from being closed because code merely exists.
