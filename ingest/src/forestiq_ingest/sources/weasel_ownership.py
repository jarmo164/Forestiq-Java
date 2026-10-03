from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Iterable
from uuid import UUID

from forestiq_ingest.http.client import HttpRequestContext, HttpSourceClient
from forestiq_ingest.pipelines.base import PipelineContext, SourceMetadata, SourceRecord, fingerprint
from forestiq_ingest.sources.base import SourceAdapterSpec, SourceIdentity, SourceMode
from forestiq_ingest.sources.watermark import IncrementalCheckpoint, incremental_extract_start


@dataclass(frozen=True)
class WeaselOwnershipConfig:
    base_url: str
    organization_id: UUID
    page_size: int = 100
    max_pages: int = 10
    overlap_hours: int = 6
    initial_lookback_days: int = 14


@dataclass(frozen=True)
class WeaselPage:
    page_no: int
    total_items: int
    rows: tuple[dict, ...]
    page_size: int

    @property
    def has_more(self) -> bool:
        return self.total_items > self.page_no * self.page_size


class WeaselOwnershipAdapter:
    spec = SourceAdapterSpec(
        job_key="WEASEL_OWNERSHIP_TRANSITIONS",
        source_key="weasel_ownership_transitions",
        dataset_key="ownership-transitions",
        mode=SourceMode.INCREMENTAL,
        pipeline_factory=object,
        config_keys=("WEASEL_API_BASE_URL", "WEASEL_ORGANIZATION_ID"),
        credential_keys=("WEASEL_API_ACCESS_TOKEN",),
    )

    def __init__(self, config: WeaselOwnershipConfig, http_client: HttpSourceClient, access_token: str):
        self.config = config
        self.http_client = http_client
        self.access_token = access_token

    def source_identity(self, context: PipelineContext) -> SourceIdentity:
        return SourceIdentity(self.spec.source_key, self.spec.dataset_key, version=context.source_version)

    def updated_since(self, checkpoint: IncrementalCheckpoint | None) -> datetime | None:
        return incremental_extract_start(checkpoint, timedelta(hours=self.config.overlap_hours))

    def extraction_window(
        self,
        checkpoint: IncrementalCheckpoint | None,
        *,
        now: datetime | None = None,
    ) -> tuple[datetime, datetime]:
        end = now or datetime.now(UTC)
        if end.tzinfo is None:
            end = end.replace(tzinfo=UTC)
        start = self.updated_since(checkpoint)
        if start is None:
            start = end - timedelta(days=max(1, self.config.initial_lookback_days))
        return start, end

    def fetch_pages(
        self,
        context: PipelineContext,
        *,
        from_time: datetime,
        to_time: datetime,
        start_page: int = 1,
    ) -> Iterable[WeaselPage]:
        page_size = max(1, min(self.config.page_size, 250))
        for offset in range(self.config.max_pages):
            page = max(1, start_page) + offset
            request_context = HttpRequestContext(
                str(context.run_id),
                context.job_key,
                self.spec.source_key,
                page,
                str(page),
            )
            body = self.http_client.get_bytes(
                "owning-transitions",
                params={
                    "startTime": from_time.isoformat(),
                    "endTime": to_time.isoformat(),
                    "maxItemsOnPage": page_size,
                    "pageNo": page,
                },
                headers={"Authorization": f"Bearer {self.access_token}"},
                context=request_context,
            )
            payload = json.loads(body.decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("Weasel response root must be an object")
            rows = payload.get("items")
            if not isinstance(rows, list):
                raise ValueError("Weasel response items must be an array")
            total_items = payload.get("totalItems", len(rows))
            if not isinstance(total_items, int) or total_items < 0:
                raise ValueError("Weasel totalItems must be a non-negative integer")
            normalized_rows = tuple(self._validate_provider_row(row) for row in rows)
            result = WeaselPage(page, total_items, normalized_rows, page_size)
            yield result
            if not result.has_more:
                return

    def acquire(
        self,
        context: PipelineContext,
        checkpoint: IncrementalCheckpoint | None = None,
        *,
        to_time: datetime | None = None,
        start_page: int = 1,
    ) -> Iterable[Iterable[dict]]:
        from_time, end = self.extraction_window(checkpoint, now=to_time)
        for page in self.fetch_pages(context, from_time=from_time, to_time=end, start_page=start_page):
            yield page.rows

    @staticmethod
    def _validate_provider_row(row: object) -> dict:
        if not isinstance(row, dict):
            raise ValueError("Weasel item must be an object")
        external_id = row.get("id")
        if not isinstance(external_id, int) or external_id <= 0:
            raise ValueError("Weasel item id must be a positive integer")
        cadastral_code = str(row.get("cadastreNo") or "").strip()
        transition_at = str(row.get("createdTime") or "").strip()
        if not cadastral_code:
            raise ValueError("Weasel item cadastreNo is required")
        if not transition_at:
            raise ValueError("Weasel item createdTime is required")

        old_owners = row.get("oldOwners") or []
        new_owners = row.get("newOwners") or []
        if not isinstance(old_owners, list) or not isinstance(new_owners, list):
            raise ValueError("Weasel oldOwners/newOwners must be arrays")
        return {
            "external_id": external_id,
            "cadastral_code": cadastral_code,
            "transition_at": transition_at,
            "old_owner_identifiers": [str(value) for value in old_owners if str(value).strip()],
            "new_owner_identifiers": [str(value) for value in new_owners if str(value).strip()],
        }

    def normalize(self, context: PipelineContext, rows: Iterable[dict]) -> Iterable[SourceRecord]:
        for row in rows:
            payload = {
                "external_id": int(row["external_id"]),
                "cadastral_code": str(row["cadastral_code"]),
                "transition_at": str(row["transition_at"]),
                "old_owner_identifiers": list(row.get("old_owner_identifiers") or []),
                "new_owner_identifiers": list(row.get("new_owner_identifiers") or []),
            }
            yield SourceRecord(
                SourceMetadata(
                    self.spec.source_key,
                    str(payload["external_id"]),
                    context.run_id,
                    source_hash=fingerprint(payload),
                ),
                payload,
            )
