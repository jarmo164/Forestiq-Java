# Owner bulk import

Local large-file importer for ForestIQ owner onboarding. Use this when the admin web import's deliberately small request limits are not appropriate.

The tool follows the same operational pattern as the national-dataset/Metsaregister import tools:

- source rows are streamed instead of loaded into RAM;
- CSV/XLSX is normalized to a temporary canonical CSV;
- duplicate-code counting uses an on-disk SQLite index;
- PostgreSQL `COPY` loads a temporary staging table;
- one set-based `INSERT` publishes only safe new owners;
- existing owners are left unchanged;
- duplicate personal/registry codes inside the input file are rejected as a group.

There is **no default ForestIQ file-size or row-count cap** in this local script. `--max-rows` is optional and intended only for smoke tests. Disk space and PostgreSQL capacity are the practical limits. XLSX itself is limited by the Excel worksheet format (1,048,576 rows per sheet); for multi-million-row imports use CSV.

## Setup

From the repository root:

```bash
python3 -m venv .venv-owners
source .venv-owners/bin/activate
pip install -r scripts/owners/requirements.txt
```

Windows PowerShell:

```powershell
python -m venv .venv-owners
.\.venv-owners\Scripts\Activate.ps1
pip install -r scripts/owners/requirements.txt
```

## Dry run first

```bash
python scripts/owners/import_owners_postgis.py ./data/owners.csv --dry-run
```

The importer auto-detects comma, semicolon and tab-delimited CSV files. UTF-8 with optional BOM is the default encoding.

For XLSX, the first worksheet is used unless `--sheet` is supplied:

```bash
python scripts/owners/import_owners_postgis.py ./data/owners.xlsx --sheet Omanikud --dry-run
```

Formula cells are rejected. Convert formulas to values before bulk import.

## Import into local ForestIQ Postgres

Set the same database URL used by the other Python PostGIS importers and the target organization explicitly:

```bash
export DATABASE_URL='postgresql://forestiq:forestiq@localhost:6432/forestiq'
export FORESTIQ_ORGANIZATION_ID='11111111-1111-1111-1111-111111111111'
python scripts/owners/import_owners_postgis.py ./data/owners.csv
```

You can also pass `--database-url` and `--organization-id` directly.

## Supported columns

The canonical owner fields are:

- `displayName` — required, max 300 characters
- `personalOrRegistryCode` — required, max 32 characters
- `ownerType` — max 64 characters
- `email` — max 320 characters
- `phone` — max 64 characters
- `address` — max 4000 characters
- `info` — max 8000 characters

Common Estonian/English headers such as `Nimi`, `Registrikood`, `Isikukood`, `E-post`, `Telefon`, `Aadress` and `Info` are mapped automatically. Override mappings when needed:

```bash
python scripts/owners/import_owners_postgis.py ./data/owners.csv \
  --dry-run \
  --map displayName='Täisnimi' \
  --map personalOrRegistryCode='Kood'
```

## Safety semantics

- New owners are inserted with status `NEW`.
- Existing `(organization_id, personal_or_registry_code)` rows are skipped, not overwritten.
- If the same code occurs multiple times in the source file, none of those rows is imported.
- Required fields, lengths and the basic email check match the web owner importer.
- `--dry-run` never connects to PostgreSQL.
- The local bulk tool is intended for controlled bootstrap/admin work and does not use the web import's per-file HTTP audit workflow.

For small interactive imports where preview, rejected-row UI and web batch history are important, continue to use Admin → Andmeallikad ja sünkroonimine → Käsitsi CSV import.

## Import cleaned contact phones

`data/andmed_puhastatud.csv` is a cleaned semicolon-delimited contact file with no header:

```text
personal_or_registry_code;display_name;phone1,phone2,...;
```

Use the contact importer to update existing ForestIQ owners without creating new owners:

```bash
python scripts/owners/import_cleaned_contacts.py \
  data/andmed_puhastatud.csv \
  --database-url "$DATABASE_URL" \
  --organization-id "$FORESTIQ_ORGANIZATION_ID" \
  --dry-run

python scripts/owners/import_cleaned_contacts.py \
  data/andmed_puhastatud.csv \
  --database-url "$DATABASE_URL" \
  --organization-id "$FORESTIQ_ORGANIZATION_ID"
```

The importer stores the first cleaned number in `owner.phone` for call workflows and preserves the complete cleaned number list in `owner.info` under the `[cleaned-contact-phones]` marker. Existing non-empty `owner.phone` values are not overwritten.
