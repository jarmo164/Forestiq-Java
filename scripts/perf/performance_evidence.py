#!/usr/bin/env python3
"""Bind query-plan and MVT PASS artifacts to one release/performance identity."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_evidence(
    *,
    profile: str,
    commit: str,
    command: str,
    query_plan: dict[str, Any] | None,
    active_map: dict[str, Any] | None,
    mvt: dict[str, Any] | None,
    query_plan_hash: str | None = None,
    active_map_hash: str | None = None,
    mvt_hash: str | None = None,
) -> dict[str, Any]:
    if profile not in {"none", "standard", "performance"}:
        raise ValueError(f"unknown performance profile: {profile}")

    lowered = command.lower()
    if any(secret in lowered for secret in ("authorization:", "bearer ", "token=", "password=", "database_url=")):
        raise ValueError("performance evidence command contains a secret-bearing argument")

    if profile != "performance":
        return {
            "schemaVersion": 1,
            "profile": profile,
            "commit": commit,
            "command": command,
            "decision": "NOT_REQUIRED",
            "artifacts": {},
            "environment": {},
        }

    if not isinstance(query_plan, dict) or not isinstance(active_map, dict) or not isinstance(mvt, dict):
        raise ValueError("performance profile requires query-plan, active-map and MVT evidence")
    if query_plan.get("status") != "PASS":
        raise ValueError("query-plan evidence is not PASS")
    if active_map.get("status") != "PASS":
        raise ValueError("active-map evidence is not PASS")
    if mvt.get("passed") is not True:
        raise ValueError("MVT evidence is not PASS")
    if query_plan.get("commit") != commit:
        raise ValueError("query-plan evidence commit mismatch")
    if active_map.get("commit") != commit:
        raise ValueError("active-map evidence commit mismatch")
    if mvt.get("commit") != commit:
        raise ValueError("MVT evidence commit mismatch")

    query_env = query_plan.get("environment") or {}
    active_env = active_map.get("environment") or {}
    mvt_env = mvt.get("environment") or {}
    migration = str(query_env.get("migrationVersion") or "")
    if not migration:
        raise ValueError("query-plan evidence is missing migration/schema identity")
    if str(active_env.get("migrationVersion") or "") != migration:
        raise ValueError("query-plan and active-map schema identities differ")
    if str(mvt_env.get("migrationVersion") or "") != migration:
        raise ValueError("query-plan and MVT schema identities differ")

    catalog = str(mvt.get("catalogVersion") or "")
    generation = str(mvt.get("generationId") or "")
    if not catalog or not generation:
        raise ValueError("MVT evidence is missing catalog/generation identity")

    return {
        "schemaVersion": 1,
        "profile": profile,
        "commit": commit,
        "command": command,
        "decision": "PASS",
        "artifacts": {
            "queryPlan": {"sha256": query_plan_hash},
            "activeMap": {"sha256": active_map_hash},
            "mvt": {"sha256": mvt_hash},
        },
        "environment": {
            "postgresVersion": query_env.get("postgresVersion"),
            "migrationVersion": migration,
            "catalogVersion": catalog,
            "generationId": generation,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True, choices=("none", "standard", "performance"))
    parser.add_argument("--commit", required=True)
    parser.add_argument("--command", required=True)
    parser.add_argument("--query-plan", type=Path)
    parser.add_argument("--active-map", type=Path)
    parser.add_argument("--mvt", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    query = json.loads(args.query_plan.read_text(encoding="utf-8")) if args.query_plan else None
    active_map = json.loads(args.active_map.read_text(encoding="utf-8")) if args.active_map else None
    mvt = json.loads(args.mvt.read_text(encoding="utf-8")) if args.mvt else None
    result = build_evidence(
        profile=args.profile,
        commit=args.commit,
        command=args.command,
        query_plan=query,
        active_map=active_map,
        mvt=mvt,
        query_plan_hash=sha256(args.query_plan) if args.query_plan else None,
        active_map_hash=sha256(args.active_map) if args.active_map else None,
        mvt_hash=sha256(args.mvt) if args.mvt else None,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"performance_evidence={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
