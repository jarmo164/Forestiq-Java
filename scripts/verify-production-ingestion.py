#!/usr/bin/env python3
"""Read-only production acceptance check for ForestIQ ingestion.

No database mutation or provider request is performed. The script reads the
existing Admin API and fails closed when operational evidence is missing.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Iterable

DEFAULT_KEYS = (
    "CADASTRE_PUBLIC_DATA",
    "FOREST_NOTIFICATIONS",
    "FOREST_PLAN",
    "METSAREGISTER_WFS_MIRROR",
    "OWNER_CADASTRE_RELATIONS",
    "WEASEL_OWNERSHIP_TRANSITIONS",
    "INHERITANCE_NOTICES",
)


class VerificationError(RuntimeError):
    pass


@dataclass(frozen=True)
class ApiClient:
    base_url: str
    bearer_token: str
    timeout_seconds: float = 20.0

    def get_json(self, path: str) -> Any:
        url = self.base_url.rstrip("/") + "/" + path.lstrip("/")
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {self.bearer_token}",
                "User-Agent": "ForestIQ-production-ingestion-verifier/1.0",
            },
            method="GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                if response.status != 200:
                    raise VerificationError(f"GET {path} returned HTTP {response.status}")
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise VerificationError(f"GET {path} returned HTTP {error.code}") from error
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            raise VerificationError(f"GET {path} failed: {error.__class__.__name__}") from error


def parse_metrics(run: dict[str, Any] | None) -> dict[str, Any]:
    if not run:
        return {}
    raw = run.get("metrics")
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def consecutive_scheduled_successes(runs: Iterable[dict[str, Any]]) -> int:
    count = 0
    for run in runs:
        if run.get("trigger") != "SCHEDULED":
            continue
        if run.get("state") != "SUCCEEDED":
            break
        count += 1
    return count


def published_generation(job: dict[str, Any]) -> str | None:
    for run_name in ("latestSuccess", "latestRun"):
        metrics = parse_metrics(job.get(run_name))
        for field in ("publishedGeneration", "generation", "currentGeneration"):
            value = metrics.get(field)
            if value not in (None, ""):
                return str(value)
    value = job.get("publishedGeneration") or job.get("currentGeneration")
    return str(value) if value not in (None, "") else None


def verify_job(client: ApiClient, job: dict[str, Any], minimum_scheduled_runs: int, recent_limit: int) -> list[str]:
    key = str(job.get("key", ""))
    failures: list[str] = []
    if job.get("configurationState") != "ENABLED":
        failures.append(f"{key}: configurationState is not ENABLED")
    if job.get("currentRun"):
        failures.append(f"{key}: a run is still active")
    health = job.get("health") or {}
    if health.get("state") != "HEALTHY":
        failures.append(f"{key}: freshness state is {health.get('state') or 'missing'}")
    if published_generation(job) is None:
        failures.append(f"{key}: published/last-known-good generation is missing")
    encoded = urllib.parse.quote(key, safe="")
    runs = client.get_json(f"/api/v1/admin/integrations/{encoded}/runs?limit={recent_limit}")
    if not isinstance(runs, list):
        failures.append(f"{key}: recent runs response is not a list")
    elif consecutive_scheduled_successes(runs) < minimum_scheduled_runs:
        failures.append(f"{key}: fewer than {minimum_scheduled_runs} consecutive scheduled successes")
    return failures


def verify_operational_state(payload: Any) -> list[str]:
    failures: list[str] = []
    if not isinstance(payload, dict):
        return ["operational endpoint response is not an object"]
    for field in ("queueDepth", "oldestQueuedAgeSeconds", "runningJobs", "workerHeartbeatAgeSeconds",
                  "schedulerHeartbeatAgeSeconds"):
        if field not in payload:
            failures.append(f"operations: {field} is missing")
    if payload.get("workerHealthy") is not True:
        failures.append("operations: worker heartbeat is not healthy")
    if payload.get("schedulerHealthy") is not True:
        failures.append("operations: scheduler heartbeat is not healthy")
    return failures


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-url", default=os.getenv("FORESTIQ_PRODUCTION_API_URL", ""))
    parser.add_argument("--bearer-token", default=os.getenv("FORESTIQ_PRODUCTION_BEARER_TOKEN", ""))
    parser.add_argument("--job-keys", default=os.getenv("FORESTIQ_INGESTION_SOURCE_KEYS", ",".join(DEFAULT_KEYS)))
    parser.add_argument("--minimum-scheduled-runs", type=int, default=2)
    parser.add_argument("--recent-limit", type=int, default=20)
    parser.add_argument("--skip-operational-endpoint", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    if not args.api_url or not args.bearer_token:
        print("FAIL: API URL and bearer token are required", file=sys.stderr)
        return 2
    if args.minimum_scheduled_runs < 1 or not 1 <= args.recent_limit <= 50:
        print("FAIL: invalid verification limits", file=sys.stderr)
        return 2
    keys = tuple(dict.fromkeys(key.strip() for key in args.job_keys.split(",") if key.strip()))
    if not keys:
        print("FAIL: at least one job key is required", file=sys.stderr)
        return 2

    client = ApiClient(args.api_url, args.bearer_token)
    try:
        jobs = client.get_json("/api/v1/admin/integrations")
        if not isinstance(jobs, list):
            raise VerificationError("integrations response is not a list")
        by_key = {str(job.get("key")): job for job in jobs if isinstance(job, dict)}
        failures: list[str] = []
        for key in keys:
            job = by_key.get(key)
            if job is None:
                failures.append(f"{key}: job is missing from Admin API")
                continue
            failures.extend(verify_job(client, job, args.minimum_scheduled_runs, args.recent_limit))
        if not args.skip_operational_endpoint:
            failures.extend(verify_operational_state(
                client.get_json("/api/v1/admin/integrations/operations")
            ))
    except VerificationError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1

    if failures:
        for failure in failures:
            print(f"FAIL {failure}", file=sys.stderr)
        return 1
    print("PASS production ingestion acceptance")
    print(f"verified_job_keys={','.join(keys)}")
    print(f"minimum_scheduled_runs={args.minimum_scheduled_runs}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
