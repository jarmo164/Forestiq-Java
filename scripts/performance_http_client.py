#!/usr/bin/env python3
"""Small dependency-free keep-alive HTTP benchmark client for local gates."""

from __future__ import annotations

import argparse
import concurrent.futures
import http.client
import ssl
import time
from pathlib import Path
from urllib.parse import quote, urlsplit


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--requests", type=int, default=120)
    parser.add_argument("--concurrency", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--token", default="")
    parser.add_argument("--accept", default="application/json")
    parser.add_argument(
        "--cache-bust-param",
        default="",
        help="Append a unique query parameter to every request while retaining keep-alive connections.",
    )
    parser.add_argument(
        "--cache-bust-start",
        type=int,
        default=0,
        help="Base integer used for unique cache-bust values (defaults to current time when parameter is enabled).",
    )
    return parser.parse_args()


def connection(parsed):
    host = parsed.hostname
    if host is None:
        raise ValueError("URL must contain a host")
    port = parsed.port
    if parsed.scheme == "https":
        return http.client.HTTPSConnection(host, port or 443, timeout=30, context=ssl.create_default_context())
    if parsed.scheme == "http":
        return http.client.HTTPConnection(host, port or 80, timeout=30)
    raise ValueError("Only http/https URLs are supported")


def request_path(base_path: str, parameter: str, value: int | None) -> str:
    if not parameter or value is None:
        return base_path
    separator = "&" if "?" in base_path else "?"
    return f"{base_path}{separator}{quote(parameter, safe='')}={value}"


def worker(
    parsed,
    base_path: str,
    headers: dict[str, str],
    indices: list[int],
    cache_bust_param: str,
    cache_bust_start: int,
) -> list[tuple[str, float, int]]:
    results: list[tuple[str, float, int]] = []
    conn = connection(parsed)
    try:
        for index in indices:
            path = request_path(
                base_path,
                cache_bust_param,
                cache_bust_start + index if cache_bust_param else None,
            )
            started = time.perf_counter()
            try:
                conn.request("GET", path, headers=headers)
                response = conn.getresponse()
                payload = response.read()
                elapsed = time.perf_counter() - started
                results.append((str(response.status), elapsed, len(payload)))
                if response.getheader("Connection", "").lower() == "close":
                    conn.close()
                    conn = connection(parsed)
            except Exception:
                elapsed = time.perf_counter() - started
                results.append(("000", elapsed, 0))
                try:
                    conn.close()
                finally:
                    conn = connection(parsed)
    finally:
        conn.close()
    return results


def main() -> int:
    args = parse_args()
    if args.requests < 1 or args.concurrency < 1:
        raise SystemExit("--requests and --concurrency must be positive")
    concurrency = min(args.requests, args.concurrency)
    parsed = urlsplit(args.url)
    base_path = parsed.path or "/"
    if parsed.query:
        base_path += "?" + parsed.query
    headers = {"Accept": args.accept, "Connection": "keep-alive"}
    if args.token:
        headers["Authorization"] = "Bearer " + args.token

    cache_bust_start = args.cache_bust_start
    if args.cache_bust_param and cache_bust_start == 0:
        cache_bust_start = time.time_ns()

    work: list[list[int]] = [[] for _ in range(concurrency)]
    for index in range(args.requests):
        work[index % concurrency].append(index)

    rows: list[tuple[str, float, int]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [
            executor.submit(
                worker,
                parsed,
                base_path,
                headers,
                indices,
                args.cache_bust_param,
                cache_bust_start,
            )
            for indices in work
            if indices
        ]
        for future in concurrent.futures.as_completed(futures):
            rows.extend(future.result())

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as handle:
        for status, elapsed, size in rows:
            handle.write(f"{status},{elapsed:.6f},{size}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
