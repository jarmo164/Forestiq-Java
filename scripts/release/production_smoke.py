#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.request
from pathlib import Path


def _get(url: str, *, token: str | None = None) -> tuple[int, object]:
    headers = {"Accept": "application/json", "User-Agent": "ForestIQ-release-smoke/1"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode("utf-8", errors="replace")
            content_type = response.headers.get("Content-Type", "")
            if "json" in content_type and raw:
                return response.status, json.loads(raw)
            return response.status, {"bytes": len(raw)}
    except urllib.error.HTTPError as exc:
        return exc.code, {"error": f"http-{exc.code}"}


def smoke(
    web_url: str,
    api_url: str,
    keycloak_url: str,
    realm: str,
    token: str,
    expected_release_id: str | None = None,
    expected_commit: str | None = None,
) -> dict:
    checks: list[dict[str, object]] = []

    web_status, _ = _get(web_url.rstrip("/") + "/login")
    checks.append({"name": "web-login", "ok": 200 <= web_status < 400, "status": web_status})

    ready_status, ready = _get(api_url.rstrip("/") + "/actuator/health/readiness")
    ready_up = isinstance(ready, dict) and str(ready.get("status", "")).upper() == "UP"
    checks.append({"name": "api-readiness", "ok": ready_status == 200 and ready_up, "status": ready_status})

    discovery_url = keycloak_url.rstrip("/") + f"/realms/{realm}/.well-known/openid-configuration"
    kc_status, discovery = _get(discovery_url)
    issuer = discovery.get("issuer") if isinstance(discovery, dict) else None
    expected_issuer = keycloak_url.rstrip("/") + f"/realms/{realm}"
    checks.append({"name": "keycloak-discovery", "ok": kc_status == 200 and issuer == expected_issuer, "status": kc_status})

    jobs_status, jobs = _get(api_url.rstrip("/") + "/api/v1/admin/integrations", token=token)
    jobs_ok = jobs_status == 200 and isinstance(jobs, list) and len(jobs) > 0
    checks.append({
        "name": "authenticated-integrations-query",
        "ok": jobs_ok,
        "status": jobs_status,
        "count": len(jobs) if isinstance(jobs, list) else 0,
    })

    if expected_release_id or expected_commit:
        api_info_status, api_info = _get(api_url.rstrip("/") + "/actuator/info")
        forestiq_info = api_info.get("forestiq", {}) if isinstance(api_info, dict) else {}
        api_metadata_ok = (
            api_info_status == 200
            and (not expected_release_id or forestiq_info.get("releaseId") == expected_release_id)
            and (not expected_commit or forestiq_info.get("commitSha") == expected_commit)
        )
        checks.append({
            "name": "api-release-metadata",
            "ok": api_metadata_ok,
            "status": api_info_status,
            "releaseId": forestiq_info.get("releaseId"),
            "commitSha": forestiq_info.get("commitSha"),
        })

        web_info_status, web_info = _get(web_url.rstrip("/") + "/api/build-info")
        web_metadata_ok = (
            web_info_status == 200
            and isinstance(web_info, dict)
            and (not expected_release_id or web_info.get("releaseId") == expected_release_id)
            and (not expected_commit or web_info.get("commitSha") == expected_commit)
        )
        checks.append({
            "name": "web-release-metadata",
            "ok": web_metadata_ok,
            "status": web_info_status,
            "releaseId": web_info.get("releaseId") if isinstance(web_info, dict) else None,
            "commitSha": web_info.get("commitSha") if isinstance(web_info, dict) else None,
        })

    return {
        "schemaVersion": 1,
        "releaseId": expected_release_id,
        "commit": expected_commit,
        "status": "passed" if all(bool(item["ok"]) for item in checks) else "failed",
        "checks": checks,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--web-url", required=True)
    parser.add_argument("--api-url", required=True)
    parser.add_argument("--keycloak-url", required=True)
    parser.add_argument("--realm", default="forestiq")
    parser.add_argument("--token", required=True)
    parser.add_argument("--release-id")
    parser.add_argument("--commit")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    result = smoke(
        args.web_url,
        args.api_url,
        args.keycloak_url,
        args.realm,
        args.token,
        args.release_id,
        args.commit,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for item in result["checks"]:
        print(f"{item['name']}: status={item['status']} ok={item['ok']}")
    if result["status"] != "passed":
        raise SystemExit("Production smoke verification failed; rollback is required.")
    print("Production smoke verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
