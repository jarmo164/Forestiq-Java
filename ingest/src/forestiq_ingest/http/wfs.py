from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Mapping

from forestiq_ingest.http.client import HttpRequestContext, HttpSourceClient


class PageLimitExceeded(RuntimeError):
    pass


@dataclass(frozen=True)
class WfsPage:
    batch_no: int
    start_index: int
    body: bytes


@dataclass(frozen=True)
class WfsPagingConfig:
    page_size: int = 1000
    max_pages: int = 1000
    start_index: int = 0


class WfsClient:
    def __init__(self, http: HttpSourceClient, *, path: str = ""):
        self.http = http
        self.path = path

    def pages(
        self,
        *,
        type_name: str,
        context: HttpRequestContext,
        paging: WfsPagingConfig,
        extra_params: Mapping[str, object] | None = None,
        cancel=None,
        raise_on_page_limit: bool = True,
    ) -> Iterable[WfsPage]:
        params = {
            "service": "WFS",
            "version": "2.0.0",
            "request": "GetFeature",
            "typeNames": type_name,
            "count": paging.page_size,
        }
        if extra_params:
            params.update(extra_params)

        start_index = paging.start_index
        for batch_no in range(1, paging.max_pages + 1):
            page_params = dict(params)
            page_params["startIndex"] = start_index
            page_context = HttpRequestContext(
                run_id=context.run_id,
                job_key=context.job_key,
                source=context.source,
                batch_no=batch_no,
                source_cursor=str(start_index),
            )
            body = self.http.get_bytes(self.path, params=page_params, context=page_context, cancel=cancel)
            if not body.strip() or _looks_like_empty_feature_collection(body):
                return
            yield WfsPage(batch_no=batch_no, start_index=start_index, body=body)
            start_index += paging.page_size

        if raise_on_page_limit:
            raise PageLimitExceeded(f"WFS page limit {paging.max_pages} reached before end-of-data signal")


def _looks_like_empty_feature_collection(body: bytes) -> bool:
    normalized = body[:4096].replace(b" ", b"").replace(b"\n", b"").replace(b"\t", b"")
    return (
        b'"features":[]' in normalized
        or b'numberReturned="0"' in normalized
        or b"numberReturned='0'" in normalized
    )
