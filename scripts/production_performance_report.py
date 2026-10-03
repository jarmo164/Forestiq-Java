#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
from pathlib import Path

REQUIRED = (
    "window_start",
    "workflow",
    "sample_count",
    "p50_ms",
    "p95_ms",
    "p99_ms",
    "error_rate_percent",
)
OPTIONAL = (
    "api_cpu_percent",
    "api_memory_mb",
    "db_p95_ms",
    "db_pool_pressure_percent",
    "mvt_cache_hit_percent",
    "background_job_p95_ms",
    "response_p95_bytes",
)
FORBIDDEN_TOKENS = (
    "authorization",
    "bearer",
    "token",
    "password",
    "secret",
    "email",
    "phone",
    "address",
    "personal",
    "registry_code",
    "payload",
    "request_body",
    "response_body",
    "query_text",
)


def _number(row: dict[str, str], key: str) -> float | None:
    value = (row.get(key) or "").strip()
    if not value:
        return None
    return float(value)


def load(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        fields = [field.strip() for field in (reader.fieldnames or [])]
        missing = [name for name in REQUIRED if name not in fields]
        if missing:
            raise ValueError(f"Missing required columns: {', '.join(missing)}")
        unsafe = [
            field for field in fields
            if any(token in field.lower() for token in FORBIDDEN_TOKENS)
        ]
        if unsafe:
            raise ValueError(
                "Production evidence must be aggregate-only; sensitive/raw columns found: "
                + ", ".join(unsafe)
            )
        rows = list(reader)

    if not rows:
        raise ValueError("Performance evidence CSV is empty.")

    for index, row in enumerate(rows, start=2):
        sample_count = int(row["sample_count"])
        if sample_count <= 0:
            raise ValueError(f"Row {index}: sample_count must be positive.")
        p50 = _number(row, "p50_ms")
        p95 = _number(row, "p95_ms")
        p99 = _number(row, "p99_ms")
        if p50 is None or p95 is None or p99 is None or not (p50 <= p95 <= p99):
            raise ValueError(f"Row {index}: require p50_ms <= p95_ms <= p99_ms.")
        error_rate = _number(row, "error_rate_percent")
        if error_rate is None or not 0 <= error_rate <= 100:
            raise ValueError(f"Row {index}: error_rate_percent must be 0..100.")
    return rows


def report(rows: list[dict[str, str]]) -> str:
    lines = [
        "# ForestIQ production performance baseline",
        "",
        "> Aggregate operational evidence only. No request payloads, tokens or personal data.",
        "",
        "| Window | Workflow | Samples | p50 ms | p95 ms | p99 ms | Error % | DB p95 ms | CPU % | Memory MB | MVT hit % | Job p95 ms | Response p95 bytes |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in rows:
        values = [
            row.get("window_start", ""),
            row.get("workflow", ""),
            row.get("sample_count", ""),
            row.get("p50_ms", ""),
            row.get("p95_ms", ""),
            row.get("p99_ms", ""),
            row.get("error_rate_percent", ""),
            row.get("db_p95_ms", ""),
            row.get("api_cpu_percent", ""),
            row.get("api_memory_mb", ""),
            row.get("mvt_cache_hit_percent", ""),
            row.get("background_job_p95_ms", ""),
            row.get("response_p95_bytes", ""),
        ]
        lines.append("| " + " | ".join(values) + " |")

    lines += [
        "",
        "## Decision rule",
        "",
        "Create a focused tuning issue only when this baseline shows a repeatable production bottleneck. Record the affected workflow, baseline p95/p99, resource evidence, measurable target and before/after result.",
        "",
        "Do not justify Redis/Valkey, replicas, partitioning, search infrastructure or larger tiers solely from retired local-machine benchmarks.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Render aggregate ForestIQ production performance evidence.")
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    rendered = report(load(args.input))
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
        print(args.output)
    else:
        print(rendered)


if __name__ == "__main__":
    main()
