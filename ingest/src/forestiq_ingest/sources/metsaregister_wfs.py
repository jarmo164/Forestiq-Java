from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable

from forestiq_ingest.http.client import HttpRequestContext, HttpSourceClient
from forestiq_ingest.http.wfs import WfsClient, WfsPage, WfsPagingConfig
from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.base import SourceAdapterSpec, SourceIdentity, SourceMode


_SAFE_FIELD = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


@dataclass(frozen=True)
class MetsaregisterWfsConfig:
    base_url: str
    type_name: str
    page_size: int = 1000
    max_pages: int = 1000
    identity_property: str | None = None
    sort_property: str | None = None


class MetsaregisterWfsAdapter:
    spec = SourceAdapterSpec(
        job_key="METSAREGISTER_WFS_MIRROR",
        source_key="metsaregister",
        dataset_key="wfs",
        mode=SourceMode.SNAPSHOT,
        pipeline_factory=object,
        config_keys=("METSAREGISTER_WFS_BASE_URL", "METSAREGISTER_WFS_TYPE_NAME"),
        freshness_target=None,
    )

    def __init__(self, config: MetsaregisterWfsConfig, http_client: HttpSourceClient | None = None):
        self.config = config
        self.http_client = http_client or HttpSourceClient(base_url=config.base_url)
        self.wfs = WfsClient(self.http_client)

    def source_identity(self, context: PipelineContext) -> SourceIdentity:
        return SourceIdentity(
            source_key=self.spec.source_key,
            dataset_key=self.config.type_name,
            version=context.source_version,
            checksum=context.source_checksum,
        )

    def acquire_pages(
        self,
        context: PipelineContext,
        *,
        start_index: int = 0,
        cancel=None,
    ) -> Iterable[WfsPage]:
        request_context = HttpRequestContext(
            run_id=str(context.run_id),
            job_key=context.job_key,
            source=self.spec.source_key,
            source_cursor=str(start_index),
        )
        extra_params: dict[str, object] = {
            "outputFormat": "application/json",
            "srsName": "EPSG:4326",
        }
        if self.config.sort_property:
            extra_params["sortBy"] = _sort_by(self.config.sort_property)

        return self.wfs.pages(
            type_name=self.config.type_name,
            context=request_context,
            paging=WfsPagingConfig(
                page_size=self.config.page_size,
                max_pages=self.config.max_pages,
                start_index=start_index,
            ),
            extra_params=extra_params,
            cancel=cancel,
            raise_on_page_limit=False,
        )


def _sort_by(descriptor: str) -> str:
    fields = [field.strip() for field in descriptor.split(",") if field.strip()]
    if not fields or len(fields) > 8 or any(not _SAFE_FIELD.fullmatch(field) for field in fields):
        raise ValueError("Metsaregister WFS sort property is invalid")
    return ",".join(f"{field} A" for field in fields)
