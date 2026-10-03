#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

SERVICE_TO_IMAGE = {
    "api": "api",
    "web": "web",
    "ingest_scheduler": "ingest",
    "ingest_worker": "ingest",
}
FAILURE_STATES = {"build_failed", "update_failed", "canceled", "deactivated", "failed"}


def _request(url: str, token: str, method: str, payload: dict | None = None) -> object:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Render API {method} failed with HTTP {exc.code}: {detail[:500]}") from exc


def _deploy_object(value: object) -> dict:
    if isinstance(value, dict):
        nested = value.get("deploy")
        if isinstance(nested, dict):
            return nested
        return value
    raise RuntimeError("Render deploy response is not an object")


def _previous_live_deploy(service_id: str, token: str) -> dict:
    data = _request(f"https://api.render.com/v1/services/{service_id}/deploys?limit=20", token, "GET")
    if not isinstance(data, list):
        raise RuntimeError(f"Render deploy history is not a list for {service_id}")
    for item in data:
        deploy = _deploy_object(item)
        if str(deploy.get("status", "")).lower() == "live" and str(deploy.get("id", "")).startswith("dep-"):
            return deploy
    raise RuntimeError(f"No previous live deploy exists for {service_id}; refusing an unrollbackable promotion")


def _wait_deploy(service_id: str, deploy_id: str, token: str, timeout_seconds: int | None = None) -> dict:
    timeout = timeout_seconds or int(os.environ.get("FORESTIQ_RENDER_DEPLOY_TIMEOUT_SECONDS", "900"))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        data = _request(f"https://api.render.com/v1/services/{service_id}/deploys/{deploy_id}", token, "GET")
        deploy = _deploy_object(data)
        status = str(deploy.get("status", "")).lower()
        if status == "live":
            return deploy
        if status in FAILURE_STATES:
            raise RuntimeError(f"Render deploy {deploy_id} for {service_id} failed with status={status}")
        time.sleep(10)
    raise TimeoutError(f"Render deploy {deploy_id} for {service_id} did not become live within {timeout}s")


def promote(manifest: dict, service_map: dict, token: str) -> dict:
    missing = sorted(set(SERVICE_TO_IMAGE) - set(service_map))
    if missing:
        raise ValueError(f"service map missing roles: {missing}")

    deployments = []
    for role, image_key in SERVICE_TO_IMAGE.items():
        service_id = str(service_map[role]).strip()
        if not service_id.startswith("srv-"):
            raise ValueError(f"{role}: invalid Render service id")
        image_ref = manifest["images"][image_key]["reference"]
        if "@sha256:" not in image_ref:
            raise ValueError(f"{role}: mutable image reference is forbidden")

        previous = _previous_live_deploy(service_id, token)
        service_url = f"https://api.render.com/v1/services/{service_id}"
        _request(service_url, token, "PATCH", {"autoDeploy": "no", "image": {"name": image_ref}})
        created = _deploy_object(_request(
            service_url + "/deploys",
            token,
            "POST",
            {"imageUrl": image_ref, "clearCache": "do_not_clear"},
        ))
        deploy_id = str(created.get("id", ""))
        if not deploy_id.startswith("dep-"):
            raise RuntimeError(f"Render did not return a deploy id for {role}")
        live = _wait_deploy(service_id, deploy_id, token)
        deployments.append({
            "role": role,
            "serviceId": service_id,
            "image": image_key,
            "imageReference": image_ref,
            "previousDeployId": previous["id"],
            "deployId": deploy_id,
            "status": live.get("status"),
        })

    return {
        "schemaVersion": 1,
        "releaseId": manifest["releaseId"],
        "commit": manifest["commit"],
        "deployments": deployments,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--service-map-json", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    token = os.environ.get("RENDER_API_KEY", "").strip()
    if not token:
        raise SystemExit("RENDER_API_KEY is required")
    if not args.service_map_json.strip():
        raise SystemExit("RENDER_IMAGE_SERVICE_MAP_JSON is required")
    result = promote(
        json.loads(Path(args.manifest).read_text(encoding="utf-8")),
        json.loads(args.service_map_json),
        token,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
