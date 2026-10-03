from __future__ import annotations

import argparse
import json
import sys
import time
from typing import Sequence

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database import check_database


def build_health_payload(settings: IngestSettings, max_age_seconds: int, require_db: bool) -> tuple[int, dict[str, object]]:
    payload: dict[str, object] = {
        "status": "ok",
        "component": settings.component,
        "worker_id": settings.worker_id,
        "timestamp_unix": int(time.time()),
        "max_age_seconds": max_age_seconds,
        "provider_checks": "skipped",
    }

    if require_db:
        db_ok = check_database(settings)
        payload["database"] = "ok" if db_ok else "unavailable"
        if not db_ok:
            payload["status"] = "degraded"
            return 1, payload

    return 0, payload


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ForestIQ ingestion liveness check")
    parser.add_argument("--component", default=None)
    parser.add_argument("--max-age-seconds", type=int, default=120)
    parser.add_argument("--require-db", action="store_true")
    args = parser.parse_args(argv)

    settings = IngestSettings.from_env(component=args.component)
    require_db = args.require_db or settings.healthcheck_require_db
    status_code, payload = build_health_payload(settings, args.max_age_seconds, require_db)
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    return status_code


if __name__ == "__main__":
    sys.exit(main())
