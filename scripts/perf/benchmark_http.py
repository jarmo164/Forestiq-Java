#!/usr/bin/env python3
"""Small dependency-free ForestIQ HTTP benchmark and latency-budget gate.

Examples:
  python scripts/perf/benchmark_http.py \
    --base-url http://localhost:8080 \
    --path '/api/v1/owners?search=&page=0&size=20' \
    --requests 500 --concurrency 16 --p95-ms 200

  FORESTIQ_BENCHMARK_TOKEN=... python scripts/perf/benchmark_http.py \
    --base-url https://api.example.test \
    --path '/api/v1/map/land-parcels/10/550/320.pbf' \
    --requests 300 --concurrency 12 --p95-ms 300 \
    --output build/perf/tile.json
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class Sample:
    latency_ms: float
    status: int
    response_bytes: int
    error: str | None = None


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = max(0, min(len(ordered) - 1, int((len(ordered) - 1) * fraction + 0.999999)))
    return ordered[position]


def request_once(url: str, token: str | None, timeout_seconds: float) -> Sample:
    headers = {
        "Accept": "application/json, application/problem+json, application/vnd.mapbox-vector-tile",
        "User-Agent": "ForestIQ-performance-benchmark/1.0",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers, method="GET")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            body = response.read()
            return Sample((time.perf_counter() - started) * 1000.0, response.status, len(body))
    except urllib.error.HTTPError as error:
        body = error.read()
        return Sample(
            (time.perf_counter() - started) * 1000.0,
            error.code,
            len(body),
            f"HTTP {error.code}",
        )
    except Exception as error:  # network failures are benchmark failures, not crashes
        return Sample(
            (time.perf_counter() - started) * 1000.0,
            0,
            0,
            f"{type(error).__name__}: {error}",
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark a ForestIQ HTTP read path")
    parser.add_argument("--base-url", required=True, help="Backend origin, e.g. http://localhost:8080")
    parser.add_argument("--path", required=True, help="Absolute API path beginning with /")
    parser.add_argument("--requests", type=int, default=300)
    parser.add_argument("--concurrency", type=int, default=12)
    parser.add_argument("--warmup", type=int, default=20)
    parser.add_argument("--timeout-seconds", type=float, default=10.0)
    parser.add_argument("--p95-ms", type=float, default=None, help="Optional hard p95 budget")
    parser.add_argument("--max-error-rate", type=float, default=0.0, help="Allowed failed fraction, 0..1")
    parser.add_argument("--output", help="Optional JSON result path")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.requests < 1 or args.concurrency < 1 or args.warmup < 0:
        raise SystemExit("requests/concurrency must be positive and warmup must be >= 0")
    if not 0.0 <= args.max_error_rate <= 1.0:
        raise SystemExit("max-error-rate must be between 0 and 1")
    if not args.path.startswith("/"):
        raise SystemExit("path must begin with /")

    url = args.base_url.rstrip("/") + args.path
    token = os.getenv("FORESTIQ_BENCHMARK_TOKEN")

    for _ in range(args.warmup):
        request_once(url, token, args.timeout_seconds)

    wall_started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        samples = list(executor.map(
            lambda _index: request_once(url, token, args.timeout_seconds),
            range(args.requests),
        ))
    wall_seconds = time.perf_counter() - wall_started

    successful = [sample for sample in samples if 200 <= sample.status < 400 and sample.error is None]
    failed = [sample for sample in samples if sample not in successful]
    latencies = [sample.latency_ms for sample in successful]
    response_sizes = [sample.response_bytes for sample in successful]
    error_rate = len(failed) / len(samples)

    result = {
        "url": url,
        "requests": len(samples),
        "successful": len(successful),
        "failed": len(failed),
        "errorRate": error_rate,
        "concurrency": args.concurrency,
        "wallSeconds": wall_seconds,
        "requestsPerSecond": len(samples) / wall_seconds if wall_seconds else 0.0,
        "latencyMs": {
            "min": min(latencies) if latencies else 0.0,
            "mean": statistics.fmean(latencies) if latencies else 0.0,
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "p99": percentile(latencies, 0.99),
            "max": max(latencies) if latencies else 0.0,
        },
        "responseBytes": {
            "mean": statistics.fmean(response_sizes) if response_sizes else 0.0,
            "max": max(response_sizes) if response_sizes else 0,
        },
        "statusCounts": {},
        "errors": [asdict(sample) for sample in failed[:20]],
        "budget": {
            "p95Ms": args.p95_ms,
            "maxErrorRate": args.max_error_rate,
        },
    }
    for sample in samples:
        status = str(sample.status)
        result["statusCounts"][status] = result["statusCounts"].get(status, 0) + 1

    print(json.dumps(result, indent=2, sort_keys=True))
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    budget_failed = False
    if not successful:
        budget_failed = True
    if error_rate > args.max_error_rate:
        budget_failed = True
    if args.p95_ms is not None and latencies and result["latencyMs"]["p95"] > args.p95_ms:
        budget_failed = True
    return 2 if budget_failed else 0


if __name__ == "__main__":
    sys.exit(main())
