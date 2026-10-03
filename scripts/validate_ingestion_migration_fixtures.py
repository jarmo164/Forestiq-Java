#!/usr/bin/env python3
"""Validate ForestIQ ingestion migration fixtures without third-party packages."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docs" / "ingestion-migration"
FIXTURES = BASE / "fixtures"
EXPECTED_JOB_KEYS = {
    "CADASTRE_PUBLIC_DATA",
    "FOREST_NOTIFICATIONS",
    "FOREST_PLAN",
    "METSAREGISTER_WFS_MIRROR",
    "OWNER_CADASTRE_RELATIONS",
    "WEASEL_OWNERSHIP_TRANSITIONS",
    "INHERITANCE_NOTICES",
}


def load_json(path: Path, errors: list[str]) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"{path.relative_to(ROOT)}: {exc}")
        return {}


def canonical_sha256(value: object) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def text_sha256(values: list[str]) -> str:
    return hashlib.sha256("\n".join(sorted(values)).encode("utf-8")).hexdigest()


def check(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def validate_schema(schema: dict, errors: list[str]) -> None:
    check(schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema", "schema: unexpected draft", errors)
    props = schema.get("properties", {})
    enum = set(props.get("jobKey", {}).get("enum", []))
    check(enum == EXPECTED_JOB_KEYS, f"schema: jobKey enum mismatch: {sorted(enum)}", errors)
    required = set(schema.get("required", []))
    check({"repositorySha", "jobKey", "runs", "comparison", "stateSafety", "checks"} <= required, "schema: evidence required fields missing", errors)


def validate_metsaregister(data: dict, errors: list[str]) -> None:
    pages = data.get("provider", {}).get("pages", [])
    features = [feature for page in pages for feature in page.get("features", [])]
    source_ids = [str(feature.get("sourceId", "")) for feature in features]
    expected = data.get("expected", {})
    check(len(features) == expected.get("rows"), "metsaregister: row count mismatch", errors)
    check(len(set(source_ids)) == expected.get("uniqueSourceIds"), "metsaregister: unique source ID count mismatch", errors)
    check(text_sha256(source_ids) == expected.get("stableIdSha256"), "metsaregister: stableIdSha256 mismatch", errors)
    check(bool(pages) and pages[-1].get("features") == [], "metsaregister: missing explicit empty terminal page", errors)
    check(data.get("provider", {}).get("numberMatched") == len(features), "metsaregister: numberMatched mismatch", errors)


def validate_cadastre(data: dict, errors: list[str]) -> None:
    artifact = data.get("artifact", {})
    rows = artifact.get("rows", [])
    codes = [str(row.get("cadastreCode", "")) for row in rows]
    expected = data.get("expected", {})
    computed = canonical_sha256(rows)
    check(computed == artifact.get("sha256"), f"cadastre: artifact sha256 mismatch (computed {computed})", errors)
    check(len(rows) == expected.get("rows"), "cadastre: row count mismatch", errors)
    check(len(set(codes)) == expected.get("uniqueCadastreCodes"), "cadastre: unique cadastral-code count mismatch", errors)
    check(data.get("variants", {}).get("unchanged", {}).get("artifactSha256") == artifact.get("sha256"), "cadastre: unchanged checksum mismatch", errors)


def validate_owner_flows(data: dict, errors: list[str]) -> None:
    portfolio = data.get("ownerPortfolio", {})
    items = [item for page in portfolio.get("pages", []) for item in page.get("items", [])]
    relations = [(item.get("sourceOwnerId"), code) for item in items for code in item.get("cadastres", [])]
    expected = portfolio.get("expected", {})
    check(len({item.get("sourceOwnerId") for item in items}) == expected.get("owners"), "owner flows: owner count mismatch", errors)
    check(len(relations) == expected.get("providerRelations"), "owner flows: relation count mismatch", errors)
    multi_code = expected.get("multiOwnerCadastre")
    check(sum(code == multi_code for _, code in relations) == expected.get("multiOwnerCount"), "owner flows: multi-owner count mismatch", errors)

    events = data.get("weasel", {}).get("events", [])
    event_ids = [event.get("sourceEventId") for event in events]
    weasel_expected = data.get("weasel", {}).get("expected", {})
    check(len(set(event_ids)) == weasel_expected.get("uniqueEvents"), "owner flows: Weasel unique-event count mismatch", errors)
    check(len(event_ids) - len(set(event_ids)) == weasel_expected.get("duplicatesIgnored"), "owner flows: Weasel duplicate count mismatch", errors)

    notices = data.get("inheritance", {}).get("notices", [])
    notice_ids = [notice.get("sourceNoticeId") for notice in notices]
    inheritance_expected = data.get("inheritance", {}).get("expected", {})
    check(len(set(notice_ids)) == inheritance_expected.get("uniqueNotices"), "owner flows: inheritance unique-notice count mismatch", errors)
    check(len(notice_ids) - len(set(notice_ids)) == inheritance_expected.get("duplicatesIgnored"), "owner flows: inheritance duplicate count mismatch", errors)


def validate_matrix(errors: list[str]) -> None:
    path = BASE / "source-matrix.md"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"{path.relative_to(ROOT)}: {exc}")
        return
    active = text.split("## Active job keys", 1)[-1].split("Baseline assertion:", 1)[0]
    keys = set(re.findall(r"^\| `([A-Z_]+)` \|", active, flags=re.MULTILINE))
    check(keys == EXPECTED_JOB_KEYS, f"matrix: active job keys mismatch: {sorted(keys)}", errors)
    for key in EXPECTED_JOB_KEYS:
        check(f"| `{key}` |" in text, f"matrix: missing detail row for {key}", errors)
    check("PENDING_EVIDENCE" in text, "matrix: missing explicit pending evidence markers", errors)


def main() -> int:
    errors: list[str] = []
    json_paths = sorted(FIXTURES.glob("*.json"))
    expected_files = {
        "cadastre.synthetic.json",
        "metsaregister.synthetic.json",
        "owner-flows.synthetic.json",
        "parity-evidence.schema.json",
    }
    check({path.name for path in json_paths} == expected_files, "fixture set differs from validator manifest", errors)
    documents = {path.name: load_json(path, errors) for path in json_paths}
    validate_schema(documents.get("parity-evidence.schema.json", {}), errors)
    validate_metsaregister(documents.get("metsaregister.synthetic.json", {}), errors)
    validate_cadastre(documents.get("cadastre.synthetic.json", {}), errors)
    validate_owner_flows(documents.get("owner-flows.synthetic.json", {}), errors)
    validate_matrix(errors)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"Validated {len(json_paths)} JSON documents, declared hashes and {len(EXPECTED_JOB_KEYS)} job keys.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
