from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Iterable
from uuid import UUID

import psycopg

from forestiq_ingest.database.staging import CopyStageSpec, copy_stage_rows
from forestiq_ingest.http.client import HttpRequestContext, HttpSourceClient
from forestiq_ingest.pipelines.base import PipelineContext, SourceMetadata, SourceRecord, fingerprint
from forestiq_ingest.sources.base import SourceAdapterSpec, SourceIdentity, SourceMode


@dataclass(frozen=True)
class MetsisOwnerPortfolioConfig:
    base_url: str
    organization_id: UUID
    page_size: int = 1000
    max_pages: int = 10_000


class MetsisOwnerPortfolioAdapter:
    spec = SourceAdapterSpec(
        job_key="OWNER_CADASTRE_RELATIONS",
        source_key="metsis_owner_portfolio",
        dataset_key="owner-cadastre-relations",
        mode=SourceMode.SNAPSHOT,
        pipeline_factory=object,
        config_keys=("METSIS_PORTFOLIO_URI", "METSIS_PORTFOLIO_ORGANIZATION_ID"),
        credential_keys=("METSIS_PORTFOLIO_AUTHORIZATION",),
    )

    def __init__(
        self,
        config: MetsisOwnerPortfolioConfig,
        http_client: HttpSourceClient,
        authorization: str | None = None,
    ):
        self.config = config
        self.http_client = http_client
        self.authorization = authorization

    def source_identity(self, context: PipelineContext) -> SourceIdentity:
        return SourceIdentity(
            source_key=self.spec.source_key,
            dataset_key=self.spec.dataset_key,
            version=context.source_version,
            checksum=context.source_checksum,
        )

    def acquire(self, context: PipelineContext) -> Iterable[Iterable[dict]]:
        """Read the same legacy Forestek contract as the Java client.

        The endpoint is 1-based (p=1) and signals end-of-data only with an
        explicit empty array. A short non-empty response is therefore not an EOF
        marker. The legacy authorization value is sent in the test header.
        """

        headers = {"test": self.authorization} if self.authorization else {}
        for page_no in range(1, self.config.max_pages + 1):
            request_context = HttpRequestContext(
                run_id=str(context.run_id),
                job_key=context.job_key,
                source=self.spec.source_key,
                batch_no=page_no,
                source_cursor=str(page_no),
            )
            body = self.http_client.get_bytes(
                "",
                params={"p": page_no},
                headers=headers,
                context=request_context,
            )
            payload = json.loads(body.decode("utf-8"))
            if not isinstance(payload, list):
                raise ValueError("MetsIS owner portfolio response root must be an array")
            if not payload:
                return
            yield self._normalize_provider_page(payload)

        raise RuntimeError(
            f"MetsIS owner portfolio page limit {self.config.max_pages} reached before explicit end-of-data"
        )

    @staticmethod
    def _normalize_provider_page(payload: list[dict]) -> list[dict]:
        first = payload[0] if payload else {}
        if not isinstance(first, dict):
            raise ValueError("MetsIS owner portfolio row must be an object")

        if any(key in first for key in ("ownerId", "ownerName", "cadastre")):
            rows: list[dict] = []
            for raw in payload:
                if not isinstance(raw, dict):
                    raise ValueError("MetsIS owner portfolio flat row must be an object")
                owner_code = str(raw.get("ownerId") or "").strip()
                owner_name = str(raw.get("ownerName") or "").strip()
                cadastre = str(raw.get("cadastre") or "").strip()
                if not owner_code or not owner_name or not cadastre:
                    raise ValueError("MetsIS owner portfolio flat row is missing owner/cadastre fields")
                rows.append(
                    {
                        "owner_code": owner_code,
                        "owner_name": owner_name,
                        "owner_type": _infer_owner_type(owner_code),
                        "cadastral_code": cadastre,
                    }
                )
            return rows

        if "owner" in first:
            rows = []
            for raw in payload:
                if not isinstance(raw, dict):
                    raise ValueError("MetsIS owner portfolio nested row must be an object")
                owner = raw.get("owner")
                if not isinstance(owner, dict):
                    raise ValueError("MetsIS owner portfolio nested row is missing owner object")
                owner_code = str(owner.get("code") or "").strip()
                owner_name = str(owner.get("name") or "").strip()
                owner_type = str(owner.get("type") or "").strip() or _infer_owner_type(owner_code)
                if not owner_code or not owner_name:
                    raise ValueError("MetsIS owner portfolio nested owner is incomplete")

                ownings = raw.get("activeOwnings")
                if ownings is None:
                    ownings = []
                if not isinstance(ownings, list):
                    raise ValueError("MetsIS activeOwnings must be an array")
                if not ownings:
                    rows.append(
                        {
                            "owner_code": owner_code,
                            "owner_name": owner_name,
                            "owner_type": owner_type,
                            "cadastral_code": None,
                        }
                    )
                    continue
                for owning in ownings:
                    if not isinstance(owning, dict):
                        raise ValueError("MetsIS activeOwnings row must be an object")
                    cadastre = str(owning.get("cadastreNo") or "").strip()
                    if not cadastre:
                        raise ValueError("MetsIS activeOwnings row is missing cadastreNo")
                    rows.append(
                        {
                            "owner_code": owner_code,
                            "owner_name": owner_name,
                            "owner_type": owner_type,
                            "cadastral_code": cadastre,
                        }
                    )
            return rows

        raise ValueError("MetsIS owner portfolio returned an unknown row structure")

    def normalize(self, context: PipelineContext, rows: Iterable[dict]) -> Iterable[SourceRecord]:
        for row in rows:
            owner_code = str(row.get("owner_code") or "").strip()
            owner_name = str(row.get("owner_name") or "").strip()
            owner_type = str(row.get("owner_type") or "UNKNOWN").strip() or "UNKNOWN"
            cadastral_code = row.get("cadastral_code")
            cadastral_code = str(cadastral_code).strip() if cadastral_code is not None else None
            if not owner_code or not owner_name:
                raise ValueError("MetsIS normalized row is missing owner identity")

            payload = {
                "owner_code": owner_code,
                "owner_name": owner_name,
                "owner_type": owner_type,
                "cadastral_code": cadastral_code or None,
            }
            source_id = f"{owner_code}:{cadastral_code or '-'}"
            yield SourceRecord(
                metadata=SourceMetadata(
                    source=self.spec.source_key,
                    source_id=source_id,
                    ingest_run_id=context.run_id,
                    source_hash=fingerprint(payload),
                ),
                payload=payload,
            )


def stage_owner_portfolio_records(
    connection: psycopg.Connection,
    organization_id: UUID,
    run_id: UUID,
    records: Iterable[SourceRecord],
    *,
    row_number_start: int = 0,
) -> int:
    def copy_rows() -> Iterable[tuple]:
        for offset, record in enumerate(records, start=1):
            yield (
                run_id,
                organization_id,
                row_number_start + offset,
                record.payload["owner_code"],
                record.payload["owner_name"],
                record.payload["owner_type"],
                record.payload.get("cadastral_code"),
            )

    return copy_stage_rows(
        connection,
        CopyStageSpec(
            create_temp_table_sql="""
            CREATE TEMP TABLE IF NOT EXISTS owner_portfolio_import_load (
                run_id UUID NOT NULL,
                organization_id UUID NOT NULL,
                source_row_number BIGINT NOT NULL,
                owner_code VARCHAR(32) NOT NULL,
                owner_name VARCHAR(300) NOT NULL,
                owner_type VARCHAR(32) NOT NULL,
                cadastral_code VARCHAR(32)
            ) ON COMMIT DROP
            """,
            truncate_sql="TRUNCATE owner_portfolio_import_load",
            copy_sql="""
            COPY owner_portfolio_import_load
                (run_id, organization_id, source_row_number, owner_code, owner_name, owner_type, cadastral_code)
            FROM STDIN
            """,
            load_sql="""
            INSERT INTO owner_portfolio_import_stage
                (run_id, organization_id, source_row_number, owner_code, owner_name, owner_type, cadastral_code)
            SELECT run_id, organization_id, source_row_number, owner_code, owner_name, owner_type, cadastral_code
              FROM owner_portfolio_import_load
            ON CONFLICT DO NOTHING
            """,
        ),
        copy_rows(),
    )


def _infer_owner_type(owner_code: str) -> str:
    normalized = "".join(owner_code.split())
    return "COMPANY" if normalized.isdigit() and len(normalized) == 8 else "PERSON"
