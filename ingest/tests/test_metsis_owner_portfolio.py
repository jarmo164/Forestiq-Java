import json
from uuid import uuid4

import httpx

from forestiq_ingest.http.client import HttpSourceClient
from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.metsis_owner_portfolio import MetsisOwnerPortfolioAdapter, MetsisOwnerPortfolioConfig


def http_client(responses, requests=None):
    iterator = iter(responses)

    def handler(request):
        if requests is not None:
            requests.append(request)
        return next(iterator)

    return HttpSourceClient(
        base_url="https://metsis.example",
        client=httpx.Client(transport=httpx.MockTransport(handler), base_url="https://metsis.example"),
        sleep=lambda _seconds: None,
    )


def context():
    return PipelineContext(
        run_id=uuid4(),
        job_key="OWNER_CADASTRE_RELATIONS",
        source="metsis_owner_portfolio",
        chunk_size=1000,
    )


def test_metsis_legacy_contract_pages_until_explicit_empty_page():
    requests = []
    adapter = MetsisOwnerPortfolioAdapter(
        MetsisOwnerPortfolioConfig("https://metsis.example", organization_id=uuid4(), page_size=2),
        http_client([
            httpx.Response(200, content=json.dumps([
                {"ownerId": "39001010000", "ownerName": "A", "cadastre": "12345:001:0001"},
            ]).encode()),
            httpx.Response(200, content=json.dumps([
                {"ownerId": "39001010001", "ownerName": "B", "cadastre": "12345:001:0002"},
            ]).encode()),
            httpx.Response(200, content=b"[]"),
        ], requests),
        authorization="legacy-secret",
    )

    pages = list(adapter.acquire(context()))

    assert [len(page) for page in pages] == [1, 1]
    assert [request.url.params["p"] for request in requests] == ["1", "2", "3"]
    assert all(request.headers["test"] == "legacy-secret" for request in requests)


def test_metsis_flat_rows_keep_multi_owner_relation_identity():
    adapter = MetsisOwnerPortfolioAdapter(
        MetsisOwnerPortfolioConfig("https://metsis.example", organization_id=uuid4()),
        http_client([]),
    )
    page = adapter._normalize_provider_page([
        {"ownerId": "39001010000", "ownerName": "A", "cadastre": "12345:001:0001"},
        {"ownerId": "39001010001", "ownerName": "B", "cadastre": "12345:001:0001"},
    ])

    records = list(adapter.normalize(context(), page))

    assert [record.payload["owner_code"] for record in records] == ["39001010000", "39001010001"]
    assert records[0].metadata.source_id == "39001010000:12345:001:0001"
    assert records[1].metadata.source_id == "39001010001:12345:001:0001"
    assert all(record.metadata.source_hash for record in records)


def test_metsis_nested_owner_without_active_cadastre_is_preserved():
    adapter = MetsisOwnerPortfolioAdapter(
        MetsisOwnerPortfolioConfig("https://metsis.example", organization_id=uuid4()),
        http_client([]),
    )

    page = adapter._normalize_provider_page([
        {
            "owner": {"code": "39001010000", "name": "A", "type": "PERSON"},
            "activeOwnings": [],
        }
    ])

    records = list(adapter.normalize(context(), page))

    assert len(records) == 1
    assert records[0].payload["cadastral_code"] is None
