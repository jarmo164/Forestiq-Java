from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

ALLOWED_CLASSIFICATIONS = {
    "ETL_ONLY",
    "REAL_TIME_DOMAIN_OPERATION",
    "RETIRED",
}
ALLOWED_CREDENTIAL_OWNERS = {"api", "ingest", "none"}


@dataclass(frozen=True)
class SourceDisposition:
    source_key: str
    classification: str
    current_java_owner: str | None
    target_job_key: str | None
    credential_owner: str
    scheduler_allowed_in_api: bool


def load_source_dispositions(path: Path) -> tuple[SourceDisposition, ...]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported source disposition schemaVersion")

    sources = payload.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("source disposition manifest must contain sources")

    seen: set[str] = set()
    result: list[SourceDisposition] = []
    for raw in sources:
        source_key = str(raw.get("sourceKey") or "").strip()
        if not source_key:
            raise ValueError("sourceKey is required")
        if source_key in seen:
            raise ValueError(f"duplicate sourceKey: {source_key}")
        seen.add(source_key)

        classification = str(raw.get("classification") or "").strip()
        if classification not in ALLOWED_CLASSIFICATIONS:
            raise ValueError(f"{source_key}: invalid classification {classification!r}")

        credential_owner = str(raw.get("credentialOwner") or "").strip()
        if credential_owner not in ALLOWED_CREDENTIAL_OWNERS:
            raise ValueError(f"{source_key}: invalid credentialOwner {credential_owner!r}")

        item = SourceDisposition(
            source_key=source_key,
            classification=classification,
            current_java_owner=(str(raw.get("currentJavaOwner")).strip() if raw.get("currentJavaOwner") else None),
            target_job_key=(str(raw.get("targetJobKey")).strip() if raw.get("targetJobKey") else None),
            credential_owner=credential_owner,
            scheduler_allowed_in_api=bool(raw.get("schedulerAllowedInApi", False)),
        )
        validate_disposition(item)
        result.append(item)

    return tuple(result)


def validate_disposition(item: SourceDisposition) -> None:
    if item.classification == "ETL_ONLY":
        if not item.target_job_key:
            raise ValueError(f"{item.source_key}: ETL_ONLY requires targetJobKey")
        if item.credential_owner != "ingest":
            raise ValueError(f"{item.source_key}: ETL_ONLY credentials must be ingest-owned")
        if item.scheduler_allowed_in_api:
            raise ValueError(f"{item.source_key}: ETL_ONLY must not be scheduled in API runtime")

    if item.classification == "REAL_TIME_DOMAIN_OPERATION":
        if not item.current_java_owner:
            raise ValueError(f"{item.source_key}: real-time exception requires currentJavaOwner")
        if item.target_job_key:
            raise ValueError(f"{item.source_key}: real-time exception must not declare targetJobKey")
        if item.credential_owner != "api":
            raise ValueError(f"{item.source_key}: real-time exception credential must remain api-owned")
        if item.scheduler_allowed_in_api:
            raise ValueError(f"{item.source_key}: real-time exception must not become a scheduler path")

    if item.classification == "RETIRED" and item.credential_owner != "none":
        raise ValueError(f"{item.source_key}: retired source must not retain credentials")
