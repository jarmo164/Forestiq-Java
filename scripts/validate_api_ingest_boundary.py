#!/usr/bin/env python3
"""Fail closed if ingestion-only runtime responsibilities leak back into the API deployment."""

from __future__ import annotations

import re
from pathlib import Path

INGEST_ONLY_API_ENV_KEYS = {
    "METSIS_PORTFOLIO_URI",
    "METSIS_PORTFOLIO_AUTHORIZATION",
    "METSIS_PORTFOLIO_ORGANIZATION_ID",
    "WEASEL_API_BASE_URL",
    "WEASEL_API_ACCESS_TOKEN",
    "WEASEL_ORGANIZATION_ID",
    "INHERITANCE_SOURCE_BASE_URL",
    "INHERITANCE_SOURCE_API_TOKEN",
    "INHERITANCE_SOURCE_URI_TEMPLATE",
    "METSAREGISTER_WFS_BASE_URL",
    "METSAREGISTER_WFS_TYPE_NAME",
    "CADASTRE_SNAPSHOT_PATH",
}

INGEST_ONLY_API_IMAGE_MARKERS = {
    "gdal-bin",
    "libgdal",
    "ogr2ogr",
}


def service_block(render_yaml: str, service_name: str) -> str:
    marker = f"    name: {service_name}\n"
    start = render_yaml.find(marker)
    if start < 0:
        raise ValueError(f"Render service not found: {service_name}")

    service_start = render_yaml.rfind("  - type:", 0, start)
    if service_start < 0:
        raise ValueError(f"Render service start not found: {service_name}")

    next_service = render_yaml.find("\n  - type:", start)
    databases = render_yaml.find("\ndatabases:", start)
    candidates = [value for value in (next_service, databases) if value >= 0]
    end = min(candidates) if candidates else len(render_yaml)
    return render_yaml[service_start:end]


def render_env_keys(block: str) -> set[str]:
    return set(re.findall(r"^\s+- key:\s+([A-Z0-9_]+)\s*$", block, flags=re.MULTILINE))


def validate_api_deployment(render_path: Path, api_dockerfile: Path) -> None:
    render_text = render_path.read_text(encoding="utf-8")
    api_block = service_block(render_text, "forestiq-api")
    api_env = render_env_keys(api_block)

    leaked = sorted(api_env & INGEST_ONLY_API_ENV_KEYS)
    if leaked:
        raise ValueError(
            "ingestion-only provider configuration leaked into forestiq-api: "
            + ", ".join(leaked)
        )

    if "FORESTIQ_RUNTIME_ROLE" not in api_env:
        raise ValueError("forestiq-api must declare FORESTIQ_RUNTIME_ROLE")

    dockerfile = api_dockerfile.read_text(encoding="utf-8").lower()
    image_leaks = sorted(marker for marker in INGEST_ONLY_API_IMAGE_MARKERS if marker in dockerfile)
    if image_leaks:
        raise ValueError(
            "ingestion-only geospatial tooling leaked into API image: "
            + ", ".join(image_leaks)
        )


def main() -> int:
    validate_api_deployment(Path("render.yaml"), Path("backend/Dockerfile"))
    print("API/ingest deployment boundary is clean.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
