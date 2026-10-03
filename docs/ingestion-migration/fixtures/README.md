# Synthetic parity fixtures

These fixtures are redacted, deterministic test inputs for source adapters and cutover comparison tooling. They contain no production personal data or provider credentials.

- `metsaregister.synthetic.json` covers paging, stable IDs, geometry bounds, schema drift and incomplete-page detection.
- `cadastre.synthetic.json` covers snapshot checksum no-op, duplicate cadastral identity, EPSG:3301 bounds and last-known-good publication.
- `owner-flows.synthetic.json` covers multi-owner relations, manual/provider conflict, end/reactivate, incremental overlap and duplicate events/notices.
- `parity-evidence.schema.json` defines the auditable evidence envelope used by the source-by-source cutover checklist.

Fixture checksums in these files describe their synthetic source payloads; they are not checksums of the JSON files themselves.
