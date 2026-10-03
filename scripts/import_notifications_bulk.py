#!/usr/bin/env python3
"""Set-based, resumable nationwide forest-notification import.

This path is intentionally separate from small per-cadastre online refreshes.
It COPYs a full snapshot to PostgreSQL staging, normalizes/deduplicates set-wise
and publishes one source generation atomically. Re-running an already-published
source checksum is a no-op.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path

DEFAULT_ORG_ID = "11111111-1111-1111-1111-111111111111"
SOURCE_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")


def run(command: list[str], *, input_text: str | None = None, capture: bool = False) -> str:
    result = subprocess.run(command, input=input_text, text=True, check=True, capture_output=capture)
    return result.stdout if capture else ""


def psql_command(args: argparse.Namespace, *, tuples: bool = False) -> list[str]:
    command = ["docker", "exec", "-i", args.container, "psql", "-X", "-v", "ON_ERROR_STOP=1"]
    if tuples:
        command += ["-Atq"]
    command += ["-U", args.user, "-d", args.database]
    return command


def execute_sql(args: argparse.Namespace, sql: str) -> None:
    run(psql_command(args), input_text=sql)


def scalar(args: argparse.Namespace, sql: str) -> str:
    output = run(psql_command(args, tuples=True), input_text=sql, capture=True).strip()
    if not output:
        raise RuntimeError("Expected scalar PostgreSQL result, got no rows")
    return output.splitlines()[0]


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def checksum(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    parser.add_argument("--archived", action="store_true")
    parser.add_argument("--source-name", default="METSAREGISTER_SNAPSHOT")
    parser.add_argument("--organization-id", default=DEFAULT_ORG_ID)
    parser.add_argument("--container", default="forestiq-postgres-1")
    parser.add_argument("--database", default="forestiq")
    parser.add_argument("--user", default="forestiq")
    parser.add_argument("--keep-stage", action="store_true")
    parser.add_argument("--max-reject-samples", type=int, default=100)
    return parser.parse_args()


def validate(args: argparse.Namespace) -> None:
    if not args.csv.is_file():
        raise SystemExit(f"CSV does not exist: {args.csv}")
    uuid.UUID(args.organization_id)
    if not SOURCE_RE.fullmatch(args.source_name):
        raise SystemExit("--source-name must match [A-Za-z0-9_.:-]{1,64}")
    if not 0 <= args.max_reject_samples <= 1000:
        raise SystemExit("--max-reject-samples must be between 0 and 1000")


def ensure_run(args: argparse.Namespace, sha256: str) -> tuple[str, str]:
    org = sql_literal(args.organization_id)
    source = sql_literal(args.source_name)
    digest = sql_literal(sha256)
    archived = "true" if args.archived else "false"
    value = scalar(args, f"""
INSERT INTO notification_import_run
    (organization_id, source_name, source_checksum, archived, status)
VALUES ({org}::uuid, {source}, {digest}, {archived}, 'STAGING')
ON CONFLICT (organization_id, source_name, source_checksum, archived)
DO UPDATE SET source_checksum=EXCLUDED.source_checksum
RETURNING id::text || '|' || status;
""")
    return tuple(value.split("|", 1))  # type: ignore[return-value]


def stage(args: argparse.Namespace, run_id: str, container_file: str) -> None:
    org = sql_literal(args.organization_id)
    run_lit = sql_literal(run_id)
    archived = "true" if args.archived else "false"
    execute_sql(args, f"""
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = '5s';

UPDATE notification_import_run
   SET status='STAGING', staged_count=0, inserted_count=0, updated_count=0,
       unchanged_count=0, deleted_count=0, rejected_count=0,
       staged_at=NULL, published_at=NULL, failed_at=NULL, last_error=NULL
 WHERE id={run_lit}::uuid AND organization_id={org}::uuid;
DELETE FROM notification_import_stage WHERE run_id={run_lit}::uuid;
DELETE FROM notification_import_reject WHERE run_id={run_lit}::uuid;

CREATE TEMP TABLE raw_notification_import (
  source_row bigserial,
  fid text, shape text, sys_id text, teatis_id text, teatise_nr text,
  kinnistu_nimetus text, kinnistu_nr text, metskond text, katastri_nr text,
  kvartali_nr text, eraldise_nr text, pindala text, too_kood text,
  raiutav_maht text, otsus text, otsuse_pohjendus text, otsuse_pojendus text,
  otsus_kinnitatud_kp text, kehtiv_kuni text, arhiveerimise_aeg text
) ON COMMIT DROP;

COPY raw_notification_import
    (fid,shape,sys_id,teatis_id,teatise_nr,kinnistu_nimetus,kinnistu_nr,metskond,
     katastri_nr,kvartali_nr,eraldise_nr,pindala,too_kood,raiutav_maht,otsus,
     otsuse_pohjendus,otsuse_pojendus,otsus_kinnitatud_kp,kehtiv_kuni,arhiveerimise_aeg)
FROM {sql_literal(container_file)}
WITH (FORMAT csv, HEADER true);

CREATE OR REPLACE FUNCTION pg_temp.try_timestamptz(value text)
RETURNS timestamptz LANGUAGE plpgsql AS $$
BEGIN
  IF value IS NULL OR btrim(value)='' THEN RETURN NULL; END IF;
  RETURN btrim(value)::timestamptz;
EXCEPTION WHEN others THEN RETURN NULL;
END
$$;

CREATE TEMP TABLE normalized_notification_import AS
WITH normalized AS (
  SELECT source_row,
         left(coalesce(nullif(btrim(teatis_id),''),nullif(btrim(sys_id),'')),128) source_external_id,
         left(nullif(btrim(katastri_nr),''),32) cadastral_code,
         CASE WHEN regexp_replace(coalesce(btrim(teatise_nr),''),'[^0-9]','','g') <> ''
              THEN regexp_replace(btrim(teatise_nr),'[^0-9]','','g')::bigint END notification_number,
         CASE WHEN btrim(coalesce(eraldise_nr,'')) ~ '^[0-9]+$' THEN btrim(eraldise_nr)::bigint END cadastre_subpart_code,
         left(nullif(btrim(too_kood),''),32) work_code,
         left(nullif(btrim(otsus),''),512) decision,
         CASE WHEN pg_temp.try_timestamptz(otsus_kinnitatud_kp) IS NULL THEN NULL
              ELSE floor(extract(epoch from pg_temp.try_timestamptz(otsus_kinnitatud_kp))*1000)::bigint END registration_date,
         CASE WHEN pg_temp.try_timestamptz(otsus_kinnitatud_kp) IS NULL THEN NULL
              ELSE floor(extract(epoch from pg_temp.try_timestamptz(otsus_kinnitatud_kp))*1000)::bigint END confirmation_date,
         CASE WHEN pg_temp.try_timestamptz(arhiveerimise_aeg) IS NULL THEN NULL
              ELSE floor(extract(epoch from pg_temp.try_timestamptz(arhiveerimise_aeg))*1000)::bigint END archive_date,
         CASE WHEN replace(btrim(coalesce(pindala,'')),',','.') ~ '^-?[0-9]+([.][0-9]+)?$'
              THEN replace(btrim(pindala),',','.')::numeric END area,
         CASE WHEN replace(btrim(coalesce(raiutav_maht,'')),',','.') ~ '^-?[0-9]+([.][0-9]+)?$'
              THEN replace(btrim(raiutav_maht),',','.')::numeric END amount_to_be_cut
    FROM raw_notification_import
), classified AS (
  SELECT n.*,
         CASE WHEN source_external_id IS NULL THEN 'missing source id'
              WHEN cadastral_code IS NULL THEN 'missing cadastral code'
              WHEN notification_number IS NULL THEN 'missing notification number'
              ELSE NULL END reject_reason
    FROM normalized n
)
SELECT * FROM classified;

INSERT INTO notification_import_reject (run_id,source_row,reason,sample)
SELECT {run_lit}::uuid,source_row,reject_reason,
       jsonb_build_object('sourceExternalId',source_external_id,'cadastralCode',cadastral_code)
  FROM normalized_notification_import
 WHERE reject_reason IS NOT NULL
 ORDER BY source_row
 LIMIT {args.max_reject_samples};

INSERT INTO notification_import_stage
    (run_id,source_row,source_external_id,cadastral_code,notification_number,
     cadastre_subpart_code,work_code,decision,registration_date,confirmation_date,
     archive_date,archived,area,amount_to_be_cut,source_hash)
WITH valid AS (
  SELECT *,row_number() OVER (PARTITION BY source_external_id ORDER BY source_row DESC) duplicate_rank
    FROM normalized_notification_import
   WHERE reject_reason IS NULL
)
SELECT {run_lit}::uuid,source_row,source_external_id,cadastral_code,notification_number,
       cadastre_subpart_code,work_code,decision,registration_date,confirmation_date,
       archive_date,{archived},area,amount_to_be_cut,
       md5(concat_ws('|',source_external_id,cadastral_code,notification_number::text,
           coalesce(cadastre_subpart_code::text,''),coalesce(work_code,''),coalesce(decision,''),
           coalesce(registration_date::text,''),coalesce(confirmation_date::text,''),
           coalesce(archive_date::text,''),{archived}::text,coalesce(area::text,''),
           coalesce(amount_to_be_cut::text,'')))
  FROM valid WHERE duplicate_rank=1;

UPDATE notification_import_run
   SET status='READY',
       staged_count=(SELECT count(*) FROM notification_import_stage WHERE run_id={run_lit}::uuid),
       rejected_count=(SELECT count(*) FROM normalized_notification_import WHERE reject_reason IS NOT NULL),
       staged_at=CURRENT_TIMESTAMP
 WHERE id={run_lit}::uuid;
COMMIT;
""")


def database_metrics(args: argparse.Namespace) -> dict[str, int]:
    row = scalar(args, """
SELECT pg_database_size(current_database())::text || '|' ||
       (SELECT wal_bytes::bigint FROM pg_stat_wal)::text || '|' ||
       COALESCE((SELECT n_dead_tup FROM pg_stat_user_tables WHERE relname='cadastre_notification'),0)::text || '|' ||
       (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event IS NOT NULL)::text;
""")
    db_bytes, wal_bytes, dead_tuples, waiting = map(int, row.split("|"))
    return {"databaseBytes": db_bytes, "walBytes": wal_bytes,
            "deadTuples": dead_tuples, "waitingSessions": waiting}


def publish(args: argparse.Namespace, run_id: str) -> dict[str, int | float]:
    org = sql_literal(args.organization_id)
    source = sql_literal(args.source_name)
    run_lit = sql_literal(run_id)
    archived = "true" if args.archived else "false"
    before = database_metrics(args)
    started = time.monotonic()

    execute_sql(args, f"""
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = '10s';
SELECT pg_advisory_xact_lock(hashtextextended(
    {sql_literal(args.organization_id + ':' + args.source_name + ':' + str(args.archived))},0));

UPDATE notification_import_run SET status='PUBLISHING',last_error=NULL
 WHERE id={run_lit}::uuid AND status IN ('READY','FAILED','PUBLISHING');

-- Registry-only parcel shells deliberately have no fabricated owner. owner_id is
-- nullable and real ownership is populated independently by ownership sources.
-- This also avoids a global placeholder-owner UUID colliding across tenants.
INSERT INTO land_parcel (organization_id,owner_id,cadastral_code,area_hectares,name,marked)
SELECT {org}::uuid,NULL,s.cadastral_code,0,s.cadastral_code,false
  FROM notification_import_stage s
 WHERE s.run_id={run_lit}::uuid
 GROUP BY s.cadastral_code
ON CONFLICT (organization_id,cadastral_code) DO NOTHING;

-- Adopt exact rows from the old id-based importer. V36 nulls source_hash on this
-- metadata-only adoption so the first generation publish must reconcile fields.
UPDATE cadastre_notification cn
   SET source_name={source},source_external_id=s.source_external_id,
       source_hash=s.source_hash,source_generation={run_lit}::uuid
  FROM notification_import_stage s
 WHERE s.run_id={run_lit}::uuid
   AND cn.organization_id={org}::uuid
   AND cn.source_name IS NULL
   AND cn.cadastral_code=s.cadastral_code
   AND cn.id::text=s.source_external_id;

CREATE TEMP TABLE notification_publish_counts AS
SELECT count(*) FILTER (WHERE cn.id IS NULL)::bigint inserted_count,
       count(*) FILTER (WHERE cn.id IS NOT NULL AND cn.source_hash IS DISTINCT FROM s.source_hash)::bigint updated_count,
       count(*) FILTER (WHERE cn.id IS NOT NULL AND cn.source_hash IS NOT DISTINCT FROM s.source_hash)::bigint unchanged_count
  FROM notification_import_stage s
  LEFT JOIN cadastre_notification cn
    ON cn.organization_id={org}::uuid AND cn.source_name={source}
   AND cn.source_external_id=s.source_external_id
 WHERE s.run_id={run_lit}::uuid;

INSERT INTO cadastre_notification
    (organization_id,cadastral_code,notification_number,cadastre_subpart_code,
     work_code,decision,registration_date,confirmation_date,archive_date,archived,
     area,amount_to_be_cut,source_name,source_external_id,source_hash,
     source_generation,source_seen_at)
SELECT {org}::uuid,s.cadastral_code,s.notification_number,s.cadastre_subpart_code,
       s.work_code,s.decision,s.registration_date,s.confirmation_date,s.archive_date,
       s.archived,s.area,s.amount_to_be_cut,{source},s.source_external_id,s.source_hash,
       {run_lit}::uuid,CURRENT_TIMESTAMP
  FROM notification_import_stage s
 WHERE s.run_id={run_lit}::uuid
ON CONFLICT (organization_id,source_name,source_external_id)
WHERE source_name IS NOT NULL AND source_external_id IS NOT NULL
DO UPDATE SET
    cadastral_code=EXCLUDED.cadastral_code,
    notification_number=EXCLUDED.notification_number,
    cadastre_subpart_code=EXCLUDED.cadastre_subpart_code,
    work_code=EXCLUDED.work_code,
    decision=EXCLUDED.decision,
    registration_date=EXCLUDED.registration_date,
    confirmation_date=EXCLUDED.confirmation_date,
    archive_date=EXCLUDED.archive_date,
    archived=EXCLUDED.archived,
    area=EXCLUDED.area,
    amount_to_be_cut=EXCLUDED.amount_to_be_cut,
    source_hash=EXCLUDED.source_hash,
    source_generation=EXCLUDED.source_generation,
    source_seen_at=CURRENT_TIMESTAMP
WHERE cadastre_notification.source_hash IS DISTINCT FROM EXCLUDED.source_hash
   OR cadastre_notification.archived IS DISTINCT FROM EXCLUDED.archived;

WITH stale AS (
  DELETE FROM cadastre_notification cn
   WHERE cn.organization_id={org}::uuid AND cn.source_name={source} AND cn.archived={archived}
     AND NOT EXISTS (
       SELECT 1 FROM notification_import_stage s
        WHERE s.run_id={run_lit}::uuid AND s.source_external_id=cn.source_external_id)
  RETURNING 1
), stale_count AS (SELECT count(*)::bigint n FROM stale)
UPDATE notification_import_run r
   SET inserted_count=c.inserted_count,updated_count=c.updated_count,
       unchanged_count=c.unchanged_count,deleted_count=(SELECT n FROM stale_count),
       status='PUBLISHED',published_at=CURRENT_TIMESTAMP,failed_at=NULL,last_error=NULL
  FROM notification_publish_counts c
 WHERE r.id={run_lit}::uuid;
COMMIT;

ANALYZE cadastre_notification;
""")

    elapsed = max(time.monotonic() - started, 0.001)
    after = database_metrics(args)
    counters = scalar(args, f"""
SELECT staged_count::text||'|'||inserted_count::text||'|'||updated_count::text||'|'||
       unchanged_count::text||'|'||deleted_count::text||'|'||rejected_count::text
  FROM notification_import_run WHERE id={run_lit}::uuid;
""")
    staged, inserted, updated, unchanged, deleted, rejected = map(int, counters.split("|"))
    metrics: dict[str, int | float] = {
        "durationSeconds": round(elapsed, 3),
        "rowsPerSecond": round(staged / elapsed, 2),
        "databaseGrowthBytes": after["databaseBytes"] - before["databaseBytes"],
        "walGrowthBytes": after["walBytes"] - before["walBytes"],
        "deadTuplesBefore": before["deadTuples"],
        "deadTuplesAfter": after["deadTuples"],
        "waitingSessionsBefore": before["waitingSessions"],
        "waitingSessionsAfter": after["waitingSessions"],
    }
    execute_sql(args, f"""
UPDATE notification_import_run
   SET metrics={sql_literal(json.dumps(metrics, separators=(',', ':')))}::jsonb
 WHERE id={run_lit}::uuid;
""")
    if not args.keep_stage:
        execute_sql(args, f"DELETE FROM notification_import_stage WHERE run_id={run_lit}::uuid;")
    return {"staged": staged, "inserted": inserted, "updated": updated,
            "unchanged": unchanged, "deleted": deleted, "rejected": rejected, **metrics}


def mark_failed(args: argparse.Namespace, run_id: str, error: BaseException) -> None:
    message = str(error).replace("\r", " ").replace("\n", " ")[:1000]
    try:
        execute_sql(args, f"""
UPDATE notification_import_run
   SET status='FAILED',failed_at=CURRENT_TIMESTAMP,last_error={sql_literal(message)}
 WHERE id={sql_literal(run_id)}::uuid AND status <> 'PUBLISHED';
""")
    except Exception:
        pass


def main() -> int:
    args = parse_args()
    validate(args)
    sha256 = checksum(args.csv)
    run_id, status = ensure_run(args, sha256)
    if status == "PUBLISHED":
        print(json.dumps({"runId": run_id, "status": status, "checksum": sha256, "skipped": True}))
        return 0

    container_file = f"/tmp/forestiq-notification-{run_id}.csv"
    run(["docker", "cp", str(args.csv), f"{args.container}:{container_file}"])
    run(["docker", "exec", args.container, "chmod", "0644", container_file])
    try:
        stage(args, run_id, container_file)
        metrics = publish(args, run_id)
        print(json.dumps({"runId": run_id, "status": "PUBLISHED", "checksum": sha256, **metrics}, indent=2))
        return 0
    except BaseException as error:
        mark_failed(args, run_id, error)
        raise
    finally:
        subprocess.run(["docker", "exec", args.container, "rm", "-f", container_file], check=False)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        print(f"Command failed ({exc.returncode}): {' '.join(exc.cmd)}", file=sys.stderr)
        raise SystemExit(exc.returncode)
