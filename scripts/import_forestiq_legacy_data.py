#!/usr/bin/env python3
"""Import legacy MetsIS registry data into the ForestIQ rewrite schema."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ORG_ID = "11111111-1111-1111-1111-111111111111"
IMPORT_OWNER_ID = "99999999-9999-9999-9999-999999999999"
IMPORT_OWNER_CODE = "FORESTIQ_IMPORT"

CONTAINER_FILES = {
    "boundaries": "/tmp/forestiq_kataster_boundaries.csv",
    "rik": "/tmp/forestiq_rik_katastrid.csv",
    "subparts": "/tmp/forestiq_eraldis.csv",
    "notifications": "/tmp/forestiq_teatis.csv",
    "features": "/tmp/forestiq_registry_features.csv",
}


def run(command: list[str], *, input_text: str | None = None) -> None:
    subprocess.run(command, input=input_text, text=True, check=True)


def execute_psql(args: argparse.Namespace, sql: str) -> None:
    run(
        [
            "docker",
            "exec",
            "-i",
            args.container,
            "psql",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            args.user,
            "-d",
            args.database,
        ],
        input_text=sql,
    )


def copy_to_container(args: argparse.Namespace, source: Path, target: str) -> None:
    if not source.exists():
        raise SystemExit(f"Input file does not exist: {source}")
    run(["docker", "cp", str(source), f"{args.container}:{target}"])
    run(["docker", "exec", args.container, "chmod", "0644", target])


def limit_clause(args: argparse.Namespace) -> str:
    return "" if args.max_rows is None else f"LIMIT {args.max_rows}"


def ensure_import_owner_sql(args: argparse.Namespace) -> str:
    return f"""
INSERT INTO owner (
  id, organization_id, display_name, personal_or_registry_code, status,
  assigned_to_subject, owner_type, info
) VALUES (
  '{IMPORT_OWNER_ID}', '{args.organization_id}', 'Imported Registry Data',
  '{IMPORT_OWNER_CODE}', 'NEW', 'system', 'SYSTEM',
  'System owner for legacy registry parcels without owner data.'
)
ON CONFLICT (organization_id, personal_or_registry_code) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  owner_type = EXCLUDED.owner_type,
  info = EXCLUDED.info;
"""


def import_boundaries(args: argparse.Namespace) -> None:
    copy_to_container(args, args.boundaries_csv, CONTAINER_FILES["boundaries"])
    execute_psql(
        args,
        f"""
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

{ensure_import_owner_sql(args)}

DROP TABLE IF EXISTS stg_forestiq_kataster_boundaries;
CREATE UNLOGGED TABLE stg_forestiq_kataster_boundaries (
  fid text,
  tunnus text,
  geom text
);

COPY stg_forestiq_kataster_boundaries
FROM '{CONTAINER_FILES["boundaries"]}'
WITH (FORMAT csv, HEADER true);

CREATE INDEX stg_forestiq_kataster_boundaries_tunnus_idx
ON stg_forestiq_kataster_boundaries (btrim(tunnus));

WITH source AS (
  SELECT
    btrim(tunnus) AS cadastral_code,
    ST_SetSRID(ST_GeomFromText(btrim(geom)), 3301) AS geom_3301
  FROM stg_forestiq_kataster_boundaries
  WHERE nullif(btrim(tunnus), '') IS NOT NULL
    AND nullif(btrim(geom), '') IS NOT NULL
  {limit_clause(args)}
)
INSERT INTO land_parcel (
  organization_id, owner_id, cadastral_code, area_hectares, boundary, name, marked
)
SELECT
  '{args.organization_id}'::uuid,
  '{IMPORT_OWNER_ID}'::uuid,
  cadastral_code,
  round((ST_Area(geom_3301) / 10000.0)::numeric, 4),
  ST_Transform(geom_3301, 4326),
  cadastral_code,
  false
FROM source
ON CONFLICT (organization_id, cadastral_code) DO UPDATE SET
  boundary = EXCLUDED.boundary,
  area_hectares = CASE
    WHEN land_parcel.area_hectares = 0 THEN EXCLUDED.area_hectares
    ELSE land_parcel.area_hectares
  END,
  updated_at = CURRENT_TIMESTAMP;

ANALYZE land_parcel;
COMMIT;

SELECT
  'land_parcel' AS table_name,
  count(*) AS rows,
  count(boundary) AS with_boundary
FROM land_parcel
WHERE organization_id = '{args.organization_id}';
""",
    )


def import_rik_cadastres(args: argparse.Namespace) -> None:
    copy_to_container(args, args.rik_csv, CONTAINER_FILES["rik"])
    execute_psql(
        args,
        f"""
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

{ensure_import_owner_sql(args)}

DROP TABLE IF EXISTS stg_forestiq_rik_katastrid;
CREATE UNLOGGED TABLE stg_forestiq_rik_katastrid (
  reg_nr text,
  property_type text,
  cadastral_code text,
  parcel_type text,
  address text,
  area_text text,
  real_part_nr text,
  co_ownership_part text
);

COPY stg_forestiq_rik_katastrid
FROM '{CONTAINER_FILES["rik"]}'
WITH (FORMAT csv, HEADER true, DELIMITER ';', ENCODING 'UTF8');

CREATE INDEX stg_forestiq_rik_katastrid_code_idx
ON stg_forestiq_rik_katastrid (btrim(cadastral_code));

WITH source AS (
  SELECT DISTINCT ON (btrim(cadastral_code))
    btrim(cadastral_code) AS cadastral_code,
    left(nullif(btrim(reg_nr), ''), 64) AS reg_nr,
    left(nullif(btrim(parcel_type), ''), 64) AS parcel_type,
    nullif(btrim(address), '') AS address,
    CASE
      WHEN lower(area_text) LIKE '%ha%' THEN nullif(replace(regexp_replace(area_text, '[^0-9,\\.]', '', 'g'), ',', '.'), '')::numeric
      WHEN lower(area_text) LIKE '%m2%' THEN nullif(replace(regexp_replace(area_text, '[^0-9,\\.]', '', 'g'), ',', '.'), '')::numeric / 10000.0
      ELSE NULL
    END AS area_hectares
  FROM stg_forestiq_rik_katastrid
  WHERE nullif(btrim(cadastral_code), '') IS NOT NULL
  {limit_clause(args)}
)
INSERT INTO land_parcel (
  organization_id, owner_id, cadastral_code, area_hectares, name, address, reg_nr, parcel_type, marked
)
SELECT
  '{args.organization_id}'::uuid,
  '{IMPORT_OWNER_ID}'::uuid,
  cadastral_code,
  coalesce(round(area_hectares, 4), 0),
  cadastral_code,
  address,
  reg_nr,
  parcel_type,
  false
FROM source
ON CONFLICT (organization_id, cadastral_code) DO UPDATE SET
  address = COALESCE(EXCLUDED.address, land_parcel.address),
  reg_nr = COALESCE(EXCLUDED.reg_nr, land_parcel.reg_nr),
  parcel_type = COALESCE(EXCLUDED.parcel_type, land_parcel.parcel_type),
  area_hectares = CASE
    WHEN land_parcel.area_hectares = 0 THEN EXCLUDED.area_hectares
    ELSE land_parcel.area_hectares
  END,
  updated_at = CURRENT_TIMESTAMP;

ANALYZE land_parcel;
COMMIT;

SELECT
  'land_parcel' AS table_name,
  count(*) AS rows,
  count(address) AS with_address,
  count(reg_nr) AS with_reg_nr
FROM land_parcel
WHERE organization_id = '{args.organization_id}';
""",
    )


def import_subparts(args: argparse.Namespace) -> None:
    copy_to_container(args, args.subparts_csv, CONTAINER_FILES["subparts"])
    execute_psql(
        args,
        f"""
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

{ensure_import_owner_sql(args)}

DROP TABLE IF EXISTS stg_forestiq_eraldis;
CREATE UNLOGGED TABLE stg_forestiq_eraldis (
  fid text,
  sys_id text,
  versioon text,
  source_id text,
  invent_kp text,
  registreerimise_kp text,
  katastri_nr text,
  kvartali_nr text,
  eraldise_nr text,
  pindala text,
  kuivendatud text,
  kasvukoht_kood text,
  peapuuliik_kood text,
  omandivorm_kood text,
  shape text
);

COPY stg_forestiq_eraldis
FROM '{CONTAINER_FILES["subparts"]}'
WITH (FORMAT csv, HEADER true);

CREATE INDEX stg_forestiq_eraldis_code_idx
ON stg_forestiq_eraldis (btrim(katastri_nr));

WITH codes AS (
  SELECT DISTINCT btrim(katastri_nr) AS cadastral_code
  FROM stg_forestiq_eraldis
  WHERE nullif(btrim(katastri_nr), '') IS NOT NULL
  {limit_clause(args)}
)
INSERT INTO land_parcel (organization_id, owner_id, cadastral_code, area_hectares, name, marked)
SELECT '{args.organization_id}'::uuid, '{IMPORT_OWNER_ID}'::uuid, cadastral_code, 0, cadastral_code, false
FROM codes
ON CONFLICT (organization_id, cadastral_code) DO NOTHING;

DELETE FROM cadastre_sub_part
WHERE organization_id = '{args.organization_id}'
  AND cadastral_code IN (
    SELECT DISTINCT btrim(katastri_nr)
    FROM stg_forestiq_eraldis
    WHERE nullif(btrim(katastri_nr), '') IS NOT NULL
  );

INSERT INTO cadastre_sub_part (
  organization_id, cadastral_code, sub_part_code, tree_type_code, area, polygon
)
SELECT
  '{args.organization_id}'::uuid,
  btrim(katastri_nr),
  nullif(btrim(eraldise_nr), '')::integer,
  left(nullif(btrim(peapuuliik_kood), ''), 16),
  nullif(replace(btrim(pindala), ',', '.'), '')::numeric,
  nullif(btrim(shape), '')
FROM stg_forestiq_eraldis
WHERE nullif(btrim(katastri_nr), '') IS NOT NULL
{limit_clause(args)};

ANALYZE cadastre_sub_part;
COMMIT;

SELECT 'cadastre_sub_part' AS table_name, count(*) AS rows, count(polygon) AS with_polygon
FROM cadastre_sub_part
WHERE organization_id = '{args.organization_id}';
""",
    )


def import_notifications(args: argparse.Namespace, source: Path, archived: bool) -> None:
    copy_to_container(args, source, CONTAINER_FILES["notifications"])
    execute_psql(
        args,
        f"""
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

{ensure_import_owner_sql(args)}

DROP TABLE IF EXISTS stg_forestiq_teatis;
CREATE UNLOGGED TABLE stg_forestiq_teatis (
  fid text,
  shape text,
  sys_id text,
  teatis_id text,
  teatise_nr text,
  kinnistu_nimetus text,
  kinnistu_nr text,
  metskond text,
  katastri_nr text,
  kvartali_nr text,
  eraldise_nr text,
  pindala text,
  too_kood text,
  raiutav_maht text,
  otsus text,
  otsuse_pohjendus text,
  otsuse_pojendus text,
  otsus_kinnitatud_kp text,
  kehtiv_kuni text,
  arhiveerimise_aeg text
);

COPY stg_forestiq_teatis
FROM '{CONTAINER_FILES["notifications"]}'
WITH (FORMAT csv, HEADER true);

CREATE INDEX stg_forestiq_teatis_code_idx
ON stg_forestiq_teatis (btrim(katastri_nr));

WITH codes AS (
  SELECT DISTINCT btrim(katastri_nr) AS cadastral_code
  FROM stg_forestiq_teatis
  WHERE nullif(btrim(katastri_nr), '') IS NOT NULL
  {limit_clause(args)}
)
INSERT INTO land_parcel (organization_id, owner_id, cadastral_code, area_hectares, name, marked)
SELECT '{args.organization_id}'::uuid, '{IMPORT_OWNER_ID}'::uuid, cadastral_code, 0, cadastral_code, false
FROM codes
ON CONFLICT (organization_id, cadastral_code) DO NOTHING;

DELETE FROM cadastre_notification AS cn
USING (
  SELECT DISTINCT btrim(katastri_nr) AS cadastral_code
  FROM stg_forestiq_teatis
  WHERE nullif(btrim(katastri_nr), '') IS NOT NULL
) AS source
WHERE cn.organization_id = '{args.organization_id}'
  AND cn.cadastral_code = source.cadastral_code
  AND cn.archived = {str(archived).lower()};

INSERT INTO cadastre_notification (
  id, organization_id, cadastral_code, notification_number, cadastre_subpart_code,
  work_code, state, damage_code, decision, registration_date, confirmation_date,
  archive_date, archived, area, amount_to_be_cut
)
SELECT
  COALESCE(nullif(btrim(teatis_id), '')::bigint, nullif(btrim(sys_id), '')::bigint),
  '{args.organization_id}'::uuid,
  btrim(katastri_nr),
  nullif(regexp_replace(btrim(teatise_nr), '[^0-9]', '', 'g'), '')::bigint,
  nullif(btrim(eraldise_nr), '')::bigint,
  left(nullif(btrim(too_kood), ''), 32),
  NULL,
  NULL,
  left(nullif(btrim(otsus), ''), 512),
  CASE
    WHEN nullif(btrim(otsus_kinnitatud_kp), '') IS NULL THEN NULL
    ELSE floor(extract(epoch from nullif(btrim(otsus_kinnitatud_kp), '')::timestamptz) * 1000)::bigint
  END,
  CASE
    WHEN nullif(btrim(otsus_kinnitatud_kp), '') IS NULL THEN NULL
    ELSE floor(extract(epoch from nullif(btrim(otsus_kinnitatud_kp), '')::timestamptz) * 1000)::bigint
  END,
  CASE
    WHEN nullif(btrim(arhiveerimise_aeg), '') IS NULL THEN NULL
    ELSE floor(extract(epoch from nullif(btrim(arhiveerimise_aeg), '')::timestamptz) * 1000)::bigint
  END,
  {str(archived).lower()},
  nullif(replace(btrim(pindala), ',', '.'), '')::numeric,
  nullif(replace(btrim(raiutav_maht), ',', '.'), '')::numeric
FROM stg_forestiq_teatis
WHERE nullif(btrim(katastri_nr), '') IS NOT NULL
  AND nullif(regexp_replace(btrim(teatise_nr), '[^0-9]', '', 'g'), '') IS NOT NULL
  AND COALESCE(nullif(btrim(teatis_id), ''), nullif(btrim(sys_id), '')) IS NOT NULL
{limit_clause(args)}
ON CONFLICT (id) DO UPDATE SET
  organization_id = EXCLUDED.organization_id,
  cadastral_code = EXCLUDED.cadastral_code,
  notification_number = EXCLUDED.notification_number,
  cadastre_subpart_code = EXCLUDED.cadastre_subpart_code,
  work_code = EXCLUDED.work_code,
  decision = EXCLUDED.decision,
  registration_date = EXCLUDED.registration_date,
  confirmation_date = EXCLUDED.confirmation_date,
  archive_date = EXCLUDED.archive_date,
  archived = EXCLUDED.archived,
  area = EXCLUDED.area,
  amount_to_be_cut = EXCLUDED.amount_to_be_cut;

ANALYZE cadastre_notification;
COMMIT;

SELECT archived, count(*) AS rows, count(DISTINCT cadastral_code) AS cadastres
FROM cadastre_notification
WHERE organization_id = '{args.organization_id}'
GROUP BY archived
ORDER BY archived;
""",
    )


def import_features(args: argparse.Namespace) -> None:
    copy_to_container(args, args.features_csv, CONTAINER_FILES["features"])
    execute_psql(
        args,
        f"""
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

{ensure_import_owner_sql(args)}

DROP TABLE IF EXISTS stg_forestiq_registry_features;
CREATE UNLOGGED TABLE stg_forestiq_registry_features (
  source_layer text,
  source_id text,
  cadastre_id text,
  subpart_code text,
  title text,
  work_code text,
  decision text,
  area text,
  volume text,
  event_date text,
  attributes text,
  geometry text
);

COPY stg_forestiq_registry_features
FROM '{CONTAINER_FILES["features"]}'
WITH (FORMAT csv, HEADER true);

CREATE INDEX stg_forestiq_registry_features_code_idx
ON stg_forestiq_registry_features (btrim(cadastre_id), btrim(source_layer));

WITH codes AS (
  SELECT DISTINCT btrim(cadastre_id) AS cadastral_code
  FROM stg_forestiq_registry_features
  WHERE nullif(btrim(cadastre_id), '') IS NOT NULL
  {limit_clause(args)}
)
INSERT INTO land_parcel (organization_id, owner_id, cadastral_code, area_hectares, name, marked)
SELECT '{args.organization_id}'::uuid, '{IMPORT_OWNER_ID}'::uuid, cadastral_code, 0, cadastral_code, false
FROM codes
ON CONFLICT (organization_id, cadastral_code) DO NOTHING;

DELETE FROM forest_registry_feature AS frf
USING (
  SELECT DISTINCT btrim(source_layer) AS source_layer, btrim(cadastre_id) AS cadastral_code
  FROM stg_forestiq_registry_features
  WHERE nullif(btrim(source_layer), '') IS NOT NULL
    AND nullif(btrim(cadastre_id), '') IS NOT NULL
) AS source
WHERE frf.organization_id = '{args.organization_id}'
  AND frf.source_layer = source.source_layer
  AND frf.cadastral_code = source.cadastral_code;

INSERT INTO forest_registry_feature (
  organization_id, cadastral_code, source_layer, source_id, subpart_code, title,
  work_code, decision, area, volume, event_date, attributes, geometry
)
SELECT
  '{args.organization_id}'::uuid,
  btrim(cadastre_id),
  left(btrim(source_layer), 120),
  left(btrim(source_id), 120),
  nullif(btrim(subpart_code), '')::integer,
  left(nullif(btrim(title), ''), 300),
  left(nullif(btrim(work_code), ''), 32),
  left(nullif(btrim(decision), ''), 512),
  nullif(replace(btrim(area), ',', '.'), '')::numeric,
  nullif(replace(btrim(volume), ',', '.'), '')::numeric,
  CASE
    WHEN nullif(btrim(event_date), '') IS NULL THEN NULL
    ELSE floor(extract(epoch from nullif(btrim(event_date), '')::timestamptz) * 1000)::bigint
  END,
  attributes,
  nullif(btrim(geometry), '')
FROM stg_forestiq_registry_features
WHERE nullif(btrim(source_layer), '') IS NOT NULL
  AND nullif(btrim(source_id), '') IS NOT NULL
  AND nullif(btrim(cadastre_id), '') IS NOT NULL
{limit_clause(args)};

ANALYZE forest_registry_feature;
COMMIT;

SELECT source_layer, count(*) AS rows, count(DISTINCT cadastral_code) AS cadastres
FROM forest_registry_feature
WHERE organization_id = '{args.organization_id}'
GROUP BY source_layer
ORDER BY source_layer;
""",
    )


def import_persons_dump(args: argparse.Namespace) -> None:
    copy_to_container(args, args.persons_csv, "/tmp/forestiq_persons_dump.csv")
    execute_psql(
        args,
        f"""
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

DROP TABLE IF EXISTS stg_forestiq_persons_dump;
CREATE UNLOGGED TABLE stg_forestiq_persons_dump (
  name text,
  address text,
  phone text,
  source text,
  code text
);

COPY stg_forestiq_persons_dump
FROM '/tmp/forestiq_persons_dump.csv'
WITH (FORMAT csv, HEADER false, DELIMITER ';', QUOTE '"', ENCODING 'WIN1252');

INSERT INTO persons_dump (organization_id, source, name, phone, address, code)
SELECT
  '{args.organization_id}'::uuid,
  left(nullif(btrim(source), ''), 120),
  left(nullif(btrim(name), ''), 300),
  left(nullif(btrim(phone), ''), 64),
  nullif(btrim(address), ''),
  left(nullif(btrim(code), ''), 64)
FROM stg_forestiq_persons_dump
WHERE nullif(btrim(name), '') IS NOT NULL
{limit_clause(args)};

ANALYZE persons_dump;
COMMIT;

SELECT count(*) AS persons_dump_rows
FROM persons_dump
WHERE organization_id = '{args.organization_id}';
""",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "source",
        choices=(
            "all",
            "boundaries",
            "rik",
            "subparts",
            "notifications",
            "notifications-archive",
            "features",
            "persons-dump",
        ),
    )
    parser.add_argument("--container", default="forestiq-postgres-1")
    parser.add_argument("--database", default="forestiq")
    parser.add_argument("--user", default="forestiq")
    parser.add_argument("--organization-id", default=DEFAULT_ORG_ID)
    parser.add_argument("--max-rows", type=int, default=None, help="Limit source rows for smoke tests.")
    parser.add_argument("--boundaries-csv", type=Path, default=PROJECT_ROOT / "andmed" / "kataster_ky_kehtiv_boundaries.csv")
    parser.add_argument("--rik-csv", type=Path, default=PROJECT_ROOT / "andmed" / "RIK" / "katastrid.csv")
    parser.add_argument("--subparts-csv", type=Path, default=PROJECT_ROOT / "andmed" / "eraldis_full.normalized.csv")
    parser.add_argument("--notifications-csv", type=Path, default=PROJECT_ROOT / "andmed" / "teatis_full.normalized.csv")
    parser.add_argument("--notifications-archive-csv", type=Path, default=PROJECT_ROOT / "andmed" / "teatis_arhiiv_full.normalized.csv")
    parser.add_argument("--features-csv", type=Path, default=PROJECT_ROOT / "andmed" / "wfs_registry_features_full.registry.normalized.csv")
    parser.add_argument("--persons-csv", type=Path, default=PROJECT_ROOT / "andmed" / "CSV" / "contacts.csv")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.source in ("all", "rik"):
        import_rik_cadastres(args)
    if args.source in ("all", "boundaries"):
        import_boundaries(args)
    if args.source in ("all", "subparts"):
        import_subparts(args)
    if args.source in ("all", "notifications"):
        import_notifications(args, args.notifications_csv, archived=False)
    if args.source in ("all", "notifications-archive"):
        import_notifications(args, args.notifications_archive_csv, archived=True)
    if args.source in ("all", "features"):
        import_features(args)
    if args.source == "persons-dump":
        import_persons_dump(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        print(f"Command failed with exit code {exc.returncode}: {' '.join(exc.cmd)}", file=sys.stderr)
        raise SystemExit(exc.returncode)
