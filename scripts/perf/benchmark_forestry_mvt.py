#!/usr/bin/env python3
"""Evidence gate for ForestIQ canonical forestry MVT serving.

The benchmark deliberately discovers representative non-empty tiles from the current
METSAREGISTER_FORESTRY generation instead of accepting hand-picked coordinates.
It records dense + sparse tiles across low/mid/detail zoom bands, HTTP latency and
size, and an EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) plan for the spatial candidate
query.

Required environment:
  DATABASE_URL                PostgreSQL URL for the ForestIQ database (read-only is enough)
  FORESTIQ_BENCHMARK_TOKEN    bearer token for the authenticated map API

Example:
  python scripts/perf/benchmark_forestry_mvt.py \
      --base-url http://localhost:8080 \
      --output performance/benchmark-runs/forestry-mvt-local.json

The script requires `psql` on PATH. It never writes database rows.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "backend/src/main/resources/forestry-tile-catalog.json"
WEB_MERCATOR_HALF = 20037508.342789244
WEB_MERCATOR_WORLD = WEB_MERCATOR_HALF * 2.0


@dataclass(frozen=True)
class TileTarget:
    density: str
    z: int
    x: int
    y: int
    point_count: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark canonical forestry MVT")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--layer", action="append", help="Layer key; repeatable. Defaults to every queryable catalog layer.")
    parser.add_argument("--requests", type=int, default=80, help="Warm requests per selected tile")
    parser.add_argument("--cold-requests", type=int, default=20, help="Uncached/direct DB samples per selected tile")
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--timeout-seconds", type=float, default=10.0)
    parser.add_argument("--output", required=True)
    return parser.parse_args()


def psql(sql: str) -> str:
    database_url = os.getenv("DATABASE_URL")
    if not database_url:
        raise SystemExit("DATABASE_URL is required")
    command = ["psql", database_url, "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql]
    completed = subprocess.run(command, text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"psql failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def quote_sql(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def current_generation() -> str:
    result = psql("""
        SELECT generation_id::text
          FROM geospatial_source_current
         WHERE source_key = 'METSAREGISTER_FORESTRY';
    """)
    if not result:
        raise RuntimeError("METSAREGISTER_FORESTRY has no published current generation")
    return result.splitlines()[0].strip()


def zoom_bands(min_zoom: int, max_zoom: int) -> list[int]:
    # Detail z20 is often a near-single-feature diagnostic rather than a useful load sample.
    # The three bands stay inside the declared contract and are deterministic.
    low = min_zoom
    detail = min(max_zoom, min_zoom + 6)
    mid = min(max_zoom, min_zoom + 3)
    return list(dict.fromkeys([low, mid, detail]))


def discover_tiles(generation: str, layer: str, z: int) -> list[TileTarget]:
    scale = float(2**z)
    tile_size = WEB_MERCATOR_WORLD / scale
    sql = f"""
        WITH points AS (
            SELECT ST_Transform(ST_PointOnSurface(feature.geometry), 3857) AS p
              FROM metsaregister_forestry_generation_member member
              JOIN metsaregister_wfs_feature feature
                ON feature.layer_name = member.layer_name
               AND feature.generation_id = member.layer_generation_id
             WHERE member.source_key = 'METSAREGISTER_FORESTRY'
               AND member.forestry_generation_id = {quote_sql(generation)}::uuid
               AND member.layer_name = {quote_sql(layer)}
               AND feature.geometry IS NOT NULL
        ), tiles AS (
            SELECT floor((ST_X(p) + {WEB_MERCATOR_HALF}) / {tile_size})::int AS x,
                   floor(({WEB_MERCATOR_HALF} - ST_Y(p)) / {tile_size})::int AS y,
                   count(*)::bigint AS c
              FROM points
             WHERE p IS NOT NULL AND NOT ST_IsEmpty(p)
             GROUP BY 1, 2
        ), chosen AS (
            (SELECT 'dense'::text AS density, x, y, c FROM tiles ORDER BY c DESC, x, y LIMIT 1)
            UNION ALL
            (SELECT 'sparse'::text AS density, x, y, c FROM tiles ORDER BY c ASC, x, y LIMIT 1)
        )
        SELECT density || E'\\t' || x || E'\\t' || y || E'\\t' || c
          FROM chosen
         ORDER BY density;
    """
    output = psql(sql)
    targets: list[TileTarget] = []
    for line in output.splitlines():
        if not line.strip():
            continue
        density, x, y, count = line.split("\t")
        limit = 2**z - 1
        targets.append(TileTarget(density, z, max(0, min(limit, int(x))), max(0, min(limit, int(y))), int(count)))
    return targets


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1))
    return ordered[index]


def request_once(url: str, token: str, timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.mapbox-vector-tile",
            "User-Agent": "ForestIQ-forestry-MVT-benchmark/1.0",
        },
        method="GET",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
            return {
                "status": response.status,
                "latencyMs": (time.perf_counter() - started) * 1000.0,
                "bytes": len(body),
                "featureCount": int(response.headers.get("X-ForestIQ-Feature-Count", "0")),
                "etag": response.headers.get("ETag"),
                "cacheControl": response.headers.get("Cache-Control"),
            }
    except urllib.error.HTTPError as error:
        body = error.read()
        return {
            "status": error.code,
            "latencyMs": (time.perf_counter() - started) * 1000.0,
            "bytes": len(body),
            "featureCount": 0,
            "error": body[:500].decode("utf-8", errors="replace"),
        }
    except Exception as error:
        return {
            "status": 0,
            "latencyMs": (time.perf_counter() - started) * 1000.0,
            "bytes": 0,
            "featureCount": 0,
            "error": f"{type(error).__name__}: {error}",
        }


def explain_candidate_query(generation: str, layer: str, target: TileTarget) -> dict[str, Any]:
    sql = f"""
        EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
        WITH bounds AS (SELECT ST_TileEnvelope({target.z}, {target.x}, {target.y}) AS geom)
        SELECT count(*)
          FROM metsaregister_forestry_generation_member member
          JOIN metsaregister_wfs_feature feature
            ON feature.layer_name = member.layer_name
           AND feature.generation_id = member.layer_generation_id
          CROSS JOIN bounds
         WHERE member.forestry_generation_id = {quote_sql(generation)}::uuid
           AND member.source_key = 'METSAREGISTER_FORESTRY'
           AND member.layer_name = {quote_sql(layer)}
           AND feature.geometry IS NOT NULL
           AND feature.geometry && ST_Transform(bounds.geom, 3301)
           AND ST_Intersects(feature.geometry, ST_Transform(bounds.geom, 3301));
    """
    raw = psql(sql)
    plan = json.loads(raw)
    plan_root = plan[0]
    nodes: list[dict[str, Any]] = []

    def walk(node: dict[str, Any]) -> None:
        nodes.append(node)
        for child in node.get("Plans", []):
            walk(child)

    walk(plan_root["Plan"])
    feature_nodes = [node for node in nodes if node.get("Relation Name") == "metsaregister_wfs_feature"]
    node_types = [str(node.get("Node Type")) for node in feature_nodes]
    index_names = [str(node.get("Index Name")) for node in feature_nodes if node.get("Index Name")]
    uses_spatial_index = any("metsaregister_wfs_feature_geometry_gix" in name for name in index_names)
    feature_seq_scan = any(node_type == "Seq Scan" for node_type in node_types)
    return {
        "executionTimeMs": plan_root.get("Execution Time"),
        "planningTimeMs": plan_root.get("Planning Time"),
        "featureNodeTypes": node_types,
        "featureIndexes": index_names,
        "usesSpatialIndex": uses_spatial_index,
        "featureSeqScan": feature_seq_scan,
        "sharedHitBlocks": sum(int(node.get("Shared Hit Blocks", 0) or 0) for node in nodes),
        "sharedReadBlocks": sum(int(node.get("Shared Read Blocks", 0) or 0) for node in nodes),
    }


def benchmark_target(base_url: str, token: str, generation: str, layer_contract: dict[str, Any], target: TileTarget,
                     cold_requests: int, requests: int, concurrency: int, timeout: float) -> dict[str, Any]:
    layer = layer_contract["key"]
    path = f"/api/v1/map/metsaregister/mvt/{layer}/{generation}/{target.z}/{target.x}/{target.y}.pbf"
    url = base_url.rstrip("/") + path

    cold_samples = [request_once(url, token, timeout) for _ in range(cold_requests)]
    cold_successful = [sample for sample in cold_samples if 200 <= int(sample["status"]) < 300]
    cold_latencies = [float(sample["latencyMs"]) for sample in cold_successful]
    cold_p95 = percentile(cold_latencies, 0.95)

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        warm = list(executor.map(lambda _: request_once(url, token, timeout), range(requests)))
    successful = [sample for sample in warm if 200 <= int(sample["status"]) < 300]
    latencies = [float(sample["latencyMs"]) for sample in successful]
    max_bytes = max((int(sample["bytes"]) for sample in successful), default=0)
    max_features = max((int(sample["featureCount"]) for sample in successful), default=0)
    warm_p95 = percentile(latencies, 0.95)
    failures = len(warm) - len(successful)
    plan = explain_candidate_query(generation, layer, target)

    failures_list: list[str] = []
    cold_failures = len(cold_samples) - len(cold_successful)
    if cold_failures:
        failures_list.append(f"{cold_failures} cold requests failed")
    if failures:
        failures_list.append(f"{failures} warm requests failed")
    if cold_p95 > float(layer_contract["coldP95Ms"]):
        failures_list.append("cold p95 latency budget exceeded")
    if warm_p95 > float(layer_contract["warmP95Ms"]):
        failures_list.append("warm p95 latency budget exceeded")
    if max_bytes > int(layer_contract["byteBudget"]):
        failures_list.append("byte budget exceeded")
    if max_features > int(layer_contract["featureBudget"]):
        failures_list.append("feature budget exceeded")
    if plan.get("executionTimeMs") is not None and float(plan["executionTimeMs"]) > float(layer_contract["databaseP95Ms"]):
        failures_list.append("database execution budget exceeded")

    # PostgreSQL may prefer a Seq Scan for a genuinely tiny member generation. Once a layer
    # has representative volume, a full scan is a failed architecture gate.
    layer_rows_raw = psql(f"""
        SELECT member.row_count
          FROM metsaregister_forestry_generation_member member
         WHERE member.forestry_generation_id = {quote_sql(generation)}::uuid
           AND member.layer_name = {quote_sql(layer)};
    """)
    layer_rows = int(layer_rows_raw or "0")
    if layer_rows >= 10_000 and (not plan["usesSpatialIndex"] or plan["featureSeqScan"]):
        failures_list.append("representative-volume spatial query did not use the intended GiST index path")

    return {
        "layer": layer,
        "density": target.density,
        "tile": {"z": target.z, "x": target.x, "y": target.y, "pointCount": target.point_count},
        "layerRows": layer_rows,
        "urlPath": path,
        "cold": {
            "requests": cold_requests,
            "successful": len(cold_successful),
            "failed": cold_failures,
            "latencyMs": {
                "p50": percentile(cold_latencies, 0.50),
                "p95": cold_p95,
                "p99": percentile(cold_latencies, 0.99),
                "max": max(cold_latencies) if cold_latencies else 0.0,
            },
            "maxBytes": max((int(sample["bytes"]) for sample in cold_successful), default=0),
            "maxFeatureCount": max((int(sample["featureCount"]) for sample in cold_successful), default=0),
        },
        "warm": {
            "requests": requests,
            "successful": len(successful),
            "failed": failures,
            "latencyMs": {
                "mean": statistics.fmean(latencies) if latencies else 0.0,
                "p50": percentile(latencies, 0.50),
                "p95": warm_p95,
                "p99": percentile(latencies, 0.99),
                "max": max(latencies) if latencies else 0.0,
            },
            "maxBytes": max_bytes,
            "maxFeatureCount": max_features,
        },
        "queryPlan": plan,
        "budgets": {
            "coldP95Ms": layer_contract["coldP95Ms"],
            "warmP95Ms": layer_contract["warmP95Ms"],
            "databaseP95Ms": layer_contract["databaseP95Ms"],
            "byteBudget": layer_contract["byteBudget"],
            "featureBudget": layer_contract["featureBudget"],
        },
        "passed": not failures_list,
        "failures": failures_list,
    }


def main() -> int:
    args = parse_args()
    if args.requests < 80 or args.cold_requests < 20 or args.concurrency < 1:
        raise SystemExit("benchmark requires >=80 warm requests, >=20 cold requests and positive concurrency")
    token = os.getenv("FORESTIQ_BENCHMARK_TOKEN")
    if not token:
        raise SystemExit("FORESTIQ_BENCHMARK_TOKEN is required")
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    contracts = {layer["key"]: layer for layer in catalog["layers"]}
    requested_layers = args.layer or [layer["key"] for layer in catalog["layers"] if layer.get("queryable") is True]
    unknown = [layer for layer in requested_layers if layer not in contracts]
    if unknown:
        raise SystemExit(f"Unknown forestry tile layer(s): {unknown}")

    generation = current_generation()
    results: list[dict[str, Any]] = []
    discovery: list[dict[str, Any]] = []
    for layer in requested_layers:
        contract = contracts[layer]
        for z in zoom_bands(int(contract["minZoom"]), int(contract["maxZoom"])):
            targets = discover_tiles(generation, layer, z)
            discovery.append({
                "layer": layer,
                "z": z,
                "targets": [target.__dict__ for target in targets],
            })
            for target in targets:
                results.append(benchmark_target(
                    args.base_url,
                    token,
                    generation,
                    contract,
                    target,
                    args.cold_requests,
                    args.requests,
                    args.concurrency,
                    args.timeout_seconds,
                ))

    if not results:
        raise RuntimeError("No representative non-empty forestry tiles were discovered")

    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, capture_output=True, check=False).stdout.strip()
    migration_version = psql("SELECT COALESCE(max(version),'none') FROM flyway_schema_history WHERE success;")
    postgres_version = psql("SHOW server_version;")
    layer_rows = {
        layer: int(psql(f"""SELECT COALESCE(row_count,0) FROM metsaregister_forestry_generation_member
                            WHERE forestry_generation_id={quote_sql(generation)}::uuid
                              AND layer_name={quote_sql(layer)};""") or "0")
        for layer in requested_layers
    }

    evidence = {
        "schemaVersion": 2,
        "commit": commit or "unknown",
        "environment": {"postgresVersion": postgres_version, "migrationVersion": migration_version},
        "layerRows": layer_rows,
        "catalogVersion": catalog["version"],
        "generationId": generation,
        "baseUrl": args.base_url.rstrip("/"),
        "selectionPolicy": "database-discovered dense+sparse non-empty tile per low/mid/detail zoom band",
        "discovery": discovery,
        "results": results,
        "passed": all(result["passed"] for result in results),
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2, sort_keys=True))
    return 0 if evidence["passed"] else 2


if __name__ == "__main__":
    sys.exit(main())
