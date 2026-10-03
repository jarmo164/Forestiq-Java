#!/usr/bin/env python3
"""Import cleaned owner contact phones into ForestIQ Postgres.

The cleaned source file is the semicolon-delimited export currently stored at
``data/andmed_puhastatud.csv``. It has no header and each row is:

    personal_or_registry_code;display_name;phone1,phone2,...;

The canonical owner table has only one short ``phone`` field. This importer
therefore stores the first phone as the call-ready primary phone and preserves
the complete phone list in ``owner.info`` under a deterministic marker.
"""
from __future__ import annotations

import argparse
import csv
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
PHONE_MARKER = "[cleaned-contact-phones]"
CANONICAL = ("personal_or_registry_code", "display_name", "primary_phone", "all_phones")


class ImportError(RuntimeError):
    pass


@dataclass(frozen=True)
class ContactRow:
    code: str
    display_name: str
    primary_phone: str
    all_phones: str


def normalize_phone(value: str) -> str:
    value = value.strip()
    if not value:
        return ""
    if value.startswith("+"):
        prefix = "+"
        value = value[1:]
    else:
        prefix = ""
    digits = re.sub(r"\D+", "", value)
    if not digits:
        return ""
    return prefix + digits


def parse_line(line: str, line_number: int) -> ContactRow | None:
    parts = line.rstrip("\n").split(";")
    if len(parts) < 3:
        raise ImportError(f"Line {line_number}: expected at least 3 semicolon-delimited fields.")
    code = parts[0].strip()
    display_name = parts[1].strip()
    raw_phones = parts[2].strip()
    if not code:
        raise ImportError(f"Line {line_number}: owner code is empty.")
    phones: list[str] = []
    seen: set[str] = set()
    for value in raw_phones.split(","):
        phone = normalize_phone(value)
        if phone and phone not in seen:
            phones.append(phone)
            seen.add(phone)
    if not phones:
        return None
    all_phones = ", ".join(phones)
    return ContactRow(
        code=code,
        display_name=display_name,
        primary_phone=phones[0],
        all_phones=all_phones,
    )


def normalize_source(source: Path, output: Path, encoding: str = "utf-8-sig") -> tuple[int, int, int, int]:
    total = with_phone = duplicate_phone_rows = too_long_info = 0
    seen_codes: set[str] = set()
    duplicate_codes: set[str] = set()
    with source.open("r", encoding=encoding, newline="") as src, output.open("w", encoding="utf-8", newline="") as dst:
        writer = csv.DictWriter(dst, fieldnames=CANONICAL)
        writer.writeheader()
        for line_number, line in enumerate(src, start=1):
            if not line.strip():
                continue
            total += 1
            row = parse_line(line, line_number)
            if row is None:
                continue
            with_phone += 1
            if row.code in seen_codes:
                duplicate_codes.add(row.code)
                duplicate_phone_rows += 1
            seen_codes.add(row.code)
            if len(row.all_phones) > 7600:
                too_long_info += 1
            writer.writerow(
                {
                    "personal_or_registry_code": row.code,
                    "display_name": row.display_name,
                    "primary_phone": row.primary_phone[:64],
                    "all_phones": row.all_phones[:7600],
                }
            )
    return total, with_phone, duplicate_phone_rows, too_long_info


def connect(database_url: str):
    try:
        import psycopg
    except ImportError as exc:
        raise ImportError("PostgreSQL import vajab psycopg paketti; paigalda scripts/owners/requirements.txt.") from exc
    return psycopg.connect(database_url)


def import_db(args: argparse.Namespace, normalized: Path) -> tuple[int, int, int, int]:
    with connect(args.database_url) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT 1 FROM organization WHERE id=%s::uuid", (args.organization_id,))
            if cur.fetchone() is None:
                raise ImportError(f"Organisatsiooni ei leitud: {args.organization_id}")
            cur.execute(
                """
                CREATE TEMP TABLE stg_owner_cleaned_contact (
                    personal_or_registry_code text NOT NULL,
                    display_name text,
                    primary_phone text NOT NULL,
                    all_phones text NOT NULL
                ) ON COMMIT PRESERVE ROWS
                """
            )
            with cur.copy(
                """
                COPY stg_owner_cleaned_contact
                    (personal_or_registry_code, display_name, primary_phone, all_phones)
                FROM STDIN WITH (FORMAT CSV, HEADER TRUE)
                """
            ) as copy, normalized.open("rb") as handle:
                for block in iter(lambda: handle.read(1024 * 1024), b""):
                    copy.write(block)
        conn.commit()

        with conn.cursor() as cur:
            cur.execute(
                """
                WITH latest AS (
                    SELECT DISTINCT ON (personal_or_registry_code)
                        personal_or_registry_code,
                        display_name,
                        primary_phone,
                        all_phones
                    FROM stg_owner_cleaned_contact
                    ORDER BY personal_or_registry_code, length(all_phones) DESC, primary_phone
                ),
                matched AS (
                    SELECT o.id, o.phone, o.info, l.primary_phone, l.all_phones
                    FROM owner o
                    JOIN latest l ON l.personal_or_registry_code = o.personal_or_registry_code
                    WHERE o.organization_id = %s::uuid
                )
                SELECT
                    (SELECT count(*) FROM latest),
                    count(*),
                    count(*) FILTER (WHERE nullif(btrim(coalesce(phone, '')), '') IS NULL),
                    count(*) FILTER (
                        WHERE coalesce(info, '') NOT LIKE %s
                    )
                FROM matched
                """,
                (args.organization_id, f"%{PHONE_MARKER}%"),
            )
            unique_with_phone, matched, blank_phone_matches, missing_info_marker = (int(value or 0) for value in cur.fetchone())
        if args.dry_run:
            return unique_with_phone, matched, blank_phone_matches, missing_info_marker

        with conn.transaction():
            with conn.cursor() as cur:
                cur.execute(
                    """
                    WITH latest AS (
                        SELECT DISTINCT ON (personal_or_registry_code)
                            personal_or_registry_code,
                            primary_phone,
                            all_phones
                        FROM stg_owner_cleaned_contact
                        ORDER BY personal_or_registry_code, length(all_phones) DESC, primary_phone
                    ),
                    matched AS (
                        SELECT o.id, o.info, l.primary_phone, l.all_phones
                        FROM owner o
                        JOIN latest l ON l.personal_or_registry_code = o.personal_or_registry_code
                        WHERE o.organization_id = %s::uuid
                    )
                    UPDATE owner o
                    SET
                        phone = CASE
                            WHEN nullif(btrim(coalesce(o.phone, '')), '') IS NULL THEN m.primary_phone
                            ELSE o.phone
                        END,
                        info = CASE
                            WHEN coalesce(o.info, '') LIKE %s THEN o.info
                            WHEN nullif(btrim(coalesce(o.info, '')), '') IS NULL
                                THEN %s || E'\\n' || 'Telefonid: ' || m.all_phones
                            ELSE o.info || E'\\n' || %s || E'\\n' || 'Telefonid: ' || m.all_phones
                        END,
                        updated_at = now()
                    FROM matched m
                    WHERE o.id = m.id
                      AND (
                        nullif(btrim(coalesce(o.phone, '')), '') IS NULL
                        OR coalesce(o.info, '') NOT LIKE %s
                      )
                    """,
                    (
                        args.organization_id,
                        f"%{PHONE_MARKER}%",
                        PHONE_MARKER,
                        PHONE_MARKER,
                        f"%{PHONE_MARKER}%",
                    ),
                )
                updated = max(0, cur.rowcount)
        return unique_with_phone, matched, blank_phone_matches, updated


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("input", type=Path)
    p.add_argument("--database-url", default=os.getenv("DATABASE_URL"))
    p.add_argument("--organization-id", default=os.getenv("FORESTIQ_ORGANIZATION_ID"))
    p.add_argument("--encoding", default="utf-8-sig")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--keep-normalized", type=Path)
    return p


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    normalized: Path | None = None
    try:
        if not args.input.is_file():
            raise ImportError(f"Sisendfaili ei leitud: {args.input}")
        fd, name = tempfile.mkstemp(prefix="forestiq-owner-contacts-", suffix=".csv")
        os.close(fd)
        normalized = Path(name)
        total, with_phone, duplicate_phone_rows, too_long_info = normalize_source(args.input, normalized, args.encoding)
        print(f"Source: {args.input}")
        print(f"Rows: {total}")
        print(f"Rows with phone: {with_phone}")
        print(f"Duplicate phone rows in source: {duplicate_phone_rows}")
        print(f"Rows truncated for owner.info: {too_long_info}")
        if args.keep_normalized:
            args.keep_normalized.parent.mkdir(parents=True, exist_ok=True)
            os.replace(normalized, args.keep_normalized)
            normalized = args.keep_normalized
            print(f"Normalized CSV: {normalized}")
        if not args.database_url:
            raise ImportError("DATABASE_URL or --database-url is required.")
        if not args.organization_id or not UUID_RE.fullmatch(args.organization_id):
            raise ImportError("FORESTIQ_ORGANIZATION_ID or --organization-id must be a valid UUID.")
        unique_with_phone, matched, blank_phone_matches, changed = import_db(args, normalized)
        print(f"Unique source owners with phone: {unique_with_phone}")
        print(f"Matched existing owners: {matched}")
        print(f"Matched owners with blank phone: {blank_phone_matches}")
        if args.dry_run:
            print(f"Matched owners missing cleaned contact marker: {changed}")
            print("Dry run complete; PostgreSQL was not changed.")
        else:
            print(f"Updated owners: {changed}")
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    finally:
        if normalized and not args.keep_normalized:
            normalized.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
