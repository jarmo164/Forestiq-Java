#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

MIGRATION_RE = re.compile(r"ForestIQ migration complete:\s+version=([^\s]+)")


def assemble_release_manifest(
    *,
    release_id: str,
    commit: str,
    image_manifest: dict,
    deployments: dict,
    smoke: dict,
    migration_log: str,
    workflow_run_url: str,
    performance_profile: str = "none",
    performance_evidence: dict | None = None,
) -> dict:
    if image_manifest.get("releaseId") != release_id:
        raise ValueError("image manifest release ID mismatch")
    if image_manifest.get("commit") != commit:
        raise ValueError("image manifest commit mismatch")
    if deployments.get("releaseId") != release_id or deployments.get("commit") != commit:
        raise ValueError("deployment evidence does not match release identity")
    if smoke.get("status") != "passed":
        raise ValueError("production smoke did not pass")
    if smoke.get("releaseId") != release_id or smoke.get("commit") != commit:
        raise ValueError("smoke evidence does not prove the requested release identity")

    migration_match = MIGRATION_RE.search(migration_log)
    if not migration_match:
        raise ValueError("migration version is missing from production migration log")
    migration_version = migration_match.group(1)

    if performance_profile not in {"none", "standard", "performance"}:
        raise ValueError("unknown performance profile")
    if performance_profile == "performance":
        if not isinstance(performance_evidence, dict):
            raise ValueError("performance release requires performance evidence")
        if performance_evidence.get("profile") != "performance":
            raise ValueError("performance evidence profile mismatch")
        if performance_evidence.get("decision") != "PASS":
            raise ValueError("performance evidence is not PASS")
        if performance_evidence.get("commit") != commit:
            raise ValueError("performance evidence commit mismatch")
        environment = performance_evidence.get("environment") or {}
        if str(environment.get("migrationVersion") or "") != migration_version:
            raise ValueError("performance evidence schema identity mismatch")
        if not str(environment.get("catalogVersion") or ""):
            raise ValueError("performance evidence catalog identity is missing")
        artifacts = performance_evidence.get("artifacts") or {}
        for key in ("queryPlan", "activeMap", "mvt"):
            digest = str((artifacts.get(key) or {}).get("sha256") or "")
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError(f"performance evidence artifact hash is invalid: {key}")
    elif performance_evidence is None:
        performance_evidence = {
            "schemaVersion": 1,
            "profile": performance_profile,
            "commit": commit,
            "command": "classifier-only",
            "decision": "NOT_REQUIRED",
            "artifacts": {},
            "environment": {},
        }

    images = image_manifest.get("images", {})
    for key in ("api", "web", "ingest"):
        record = images.get(key)
        if not isinstance(record, dict) or not str(record.get("reference", "")).find("@sha256:") >= 0:
            raise ValueError(f"immutable image reference missing: {key}")

    deployment_rows = deployments.get("deployments")
    if not isinstance(deployment_rows, list) or not deployment_rows:
        raise ValueError("deployment evidence is empty")

    rollback_targets = {}
    for item in deployment_rows:
        role = str(item.get("role", ""))
        deploy_id = str(item.get("deployId", ""))
        previous_id = str(item.get("previousDeployId", ""))
        image_reference = str(item.get("imageReference", ""))
        if str(item.get("status", "")).lower() != "live":
            raise ValueError(f"{role}: deployment is not live")
        if not deploy_id.startswith("dep-") or not previous_id.startswith("dep-"):
            raise ValueError(f"{role}: deploy or rollback target ID is invalid")
        if "@sha256:" not in image_reference:
            raise ValueError(f"{role}: deployed image is mutable")
        rollback_targets[role] = {
            "serviceId": item.get("serviceId"),
            "deployId": previous_id,
        }

    if not workflow_run_url.startswith("https://github.com/"):
        raise ValueError("workflow run URL is invalid")

    return {
        "schemaVersion": 2,
        "releaseId": release_id,
        "commit": commit,
        "migrationVersion": migration_version,
        "workflowRunUrl": workflow_run_url,
        "result": "success",
        "images": images,
        "deployments": deployment_rows,
        "rollbackTargets": rollback_targets,
        "smoke": smoke,
        "performance": performance_evidence,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--images", required=True)
    parser.add_argument("--deployments", required=True)
    parser.add_argument("--smoke", required=True)
    parser.add_argument("--migration-log", required=True)
    parser.add_argument("--workflow-run-url", required=True)
    parser.add_argument("--performance-profile", choices=("none", "standard", "performance"), default="none")
    parser.add_argument("--performance-evidence")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    result = assemble_release_manifest(
        release_id=args.release_id,
        commit=args.commit,
        image_manifest=json.loads(Path(args.images).read_text(encoding="utf-8")),
        deployments=json.loads(Path(args.deployments).read_text(encoding="utf-8")),
        smoke=json.loads(Path(args.smoke).read_text(encoding="utf-8")),
        migration_log=Path(args.migration_log).read_text(encoding="utf-8"),
        workflow_run_url=args.workflow_run_url,
        performance_profile=args.performance_profile,
        performance_evidence=(
            json.loads(Path(args.performance_evidence).read_text(encoding="utf-8"))
            if args.performance_evidence else None
        ),
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"release_manifest={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
