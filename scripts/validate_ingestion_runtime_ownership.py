#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "config" / "ingestion-runtime-ownership.json"


def render_service(text: str, name: str) -> str:
    marker = f"    name: {name}\n"
    start = text.find(marker)
    if start < 0:
        raise ValueError(f"Render service not found: {name}")
    service_start = text.rfind("  - type:", 0, start)
    next_service = text.find("\n  - type:", start)
    databases = text.find("\ndatabases:", start)
    candidates = [value for value in (next_service, databases) if value >= 0]
    end = min(candidates) if candidates else len(text)
    return text[service_start:end]


def compose_service(text: str, name: str) -> str:
    match = re.search(
        rf"^  {re.escape(name)}:\n(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:\n|^volumes:|\Z)",
        text,
        re.M | re.S,
    )
    if not match:
        raise ValueError(f"Compose service not found: {name}")
    return match.group(0)


def env_keys(block: str) -> set[str]:
    render = set(re.findall(r"^\s+- key:\s+([A-Z0-9_]+)\s*$", block, re.M))
    compose = set(re.findall(r"^\s+([A-Z0-9_]+):", block, re.M))
    return render | compose


def validate_texts(
    policy: dict,
    render_text: str,
    compose_text: str,
    dockerfile_text: str,
    status_text: str,
) -> None:
    forbidden = set(policy["api_runtime"]["forbidden_provider_env"])

    render_api = render_service(render_text, policy["api_runtime"]["render_service"])
    render_scheduler = render_service(render_text, policy["scheduler_runtime"]["render_service"])
    render_worker = render_service(render_text, policy["worker_runtime"]["render_service"])

    compose_api = compose_service(compose_text, policy["api_runtime"]["compose_service"])
    compose_scheduler = compose_service(compose_text, policy["scheduler_runtime"]["compose_service"])
    compose_worker = compose_service(compose_text, policy["worker_runtime"]["compose_service"])

    for label, block in (("Render API", render_api), ("Compose API", compose_api)):
        leaked = sorted(env_keys(block) & forbidden)
        if leaked:
            raise ValueError(f"{label} contains ingestion/provider keys: {', '.join(leaked)}")

    for label, block in (("Render scheduler", render_scheduler), ("Compose scheduler", compose_scheduler)):
        leaked = sorted(env_keys(block) & forbidden)
        if leaked:
            raise ValueError(f"{label} contains provider credentials: {', '.join(leaked)}")

    for label, block in (("Render worker", render_worker), ("Compose worker", compose_worker)):
        missing = sorted(
            key for key in forbidden
            if key.startswith(("METSIS_", "WEASEL_", "INHERITANCE_"))
            and key not in env_keys(block)
        )
        if missing:
            raise ValueError(f"{label} is missing worker-owned provider configuration: {', '.join(missing)}")

    lowered = dockerfile_text.lower()
    leaked_markers = [
        marker for marker in policy["api_runtime"]["forbidden_image_markers"]
        if marker.lower() in lowered
    ]
    if leaked_markers:
        raise ValueError("API image contains ingestion-only tooling: " + ", ".join(leaked_markers))

    for family in policy["python_etl_families"]:
        if family not in status_text:
            raise ValueError(f"Ingestion status matrix lost Python ETL family: {family}")

    for exception in policy["java_realtime_exceptions"]:
        if exception["name"] not in status_text:
            raise ValueError(f"Status matrix lost Java real-time exception: {exception['name']}")


def validate_repository(root: Path = ROOT) -> None:
    policy = json.loads((root / "config" / "ingestion-runtime-ownership.json").read_text(encoding="utf-8"))
    validate_texts(
        policy,
        (root / "render.yaml").read_text(encoding="utf-8"),
        (root / "compose.prod.yaml").read_text(encoding="utf-8"),
        (root / "backend" / "Dockerfile").read_text(encoding="utf-8"),
        (root / "docs" / "architecture" / "ingestion-migration-status-matrix.md").read_text(encoding="utf-8"),
    )


def main() -> int:
    validate_repository()
    print("Ingestion runtime ownership contract is valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
