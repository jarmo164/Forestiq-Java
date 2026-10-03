#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from scripts.release.render_image_promotion import _deploy_object, _request, _wait_deploy


def rollback(deployments: dict, token: str) -> dict:
    results = []
    for item in reversed(deployments.get("deployments", [])):
        service_id = str(item.get("serviceId", ""))
        previous = str(item.get("previousDeployId", ""))
        if not service_id.startswith("srv-") or not previous.startswith("dep-"):
            raise ValueError(f"rollback evidence missing for role {item.get('role')}")
        response = _request(
            f"https://api.render.com/v1/services/{service_id}/rollback",
            token,
            "POST",
            {"deployId": previous},
        )
        rollback_deploy = _deploy_object(response)
        deploy_id = str(rollback_deploy.get("id", ""))
        if not deploy_id.startswith("dep-"):
            raise RuntimeError(f"Render rollback did not return a deploy id for {service_id}")
        live = _wait_deploy(service_id, deploy_id, token)
        results.append({
            "role": item.get("role"),
            "serviceId": service_id,
            "rollbackToDeployId": previous,
            "rollbackDeployId": deploy_id,
            "status": live.get("status"),
        })
    return {
        "schemaVersion": 1,
        "releaseId": deployments.get("releaseId"),
        "commit": deployments.get("commit"),
        "rollback": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--deployments", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    token = os.environ.get("RENDER_API_KEY", "").strip()
    if not token:
        raise SystemExit("RENDER_API_KEY is required")
    data = json.loads(Path(args.deployments).read_text(encoding="utf-8"))
    result = rollback(data, token)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
