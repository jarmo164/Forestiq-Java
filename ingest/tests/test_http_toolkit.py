import httpx
import pytest

from forestiq_ingest.http.client import HttpClientConfig, HttpRequestContext, HttpSourceClient, ResponseTooLarge, redact_headers
from forestiq_ingest.http.retry import FailureClass, RetryPolicy, classify_http_status
from forestiq_ingest.http.wfs import PageLimitExceeded, WfsClient, WfsPagingConfig


def context():
    return HttpRequestContext(run_id="run-1", job_key="JOB", source="source")


def client_with_responses(responses, sleeps=None, max_response_bytes=1000):
    iterator = iter(responses)

    def handler(request):
        response = next(iterator)
        if isinstance(response, Exception):
            raise response
        return response

    transport = httpx.MockTransport(handler)
    http = HttpSourceClient(
        base_url="https://provider.example",
        client=httpx.Client(transport=transport, base_url="https://provider.example"),
        retry_policy=RetryPolicy(max_attempts=3, base_delay_seconds=1, max_delay_seconds=5, jitter_ratio=0),
        config=HttpClientConfig(max_response_bytes=max_response_bytes),
        sleep=(sleeps.append if sleeps is not None else lambda _seconds: None),
    )
    return http


def test_http_status_classification():
    assert classify_http_status(200) == FailureClass.SUCCESS
    assert classify_http_status(429) == FailureClass.RATE_LIMITED
    assert classify_http_status(503) == FailureClass.RETRYABLE
    assert classify_http_status(401) == FailureClass.TERMINAL


def test_retries_5xx_with_bounded_backoff():
    sleeps = []
    http = client_with_responses([
        httpx.Response(503),
        httpx.Response(200, content=b"ok"),
    ], sleeps=sleeps)

    assert http.get_bytes("/", context=context()) == b"ok"
    assert sleeps == [1.0]


def test_429_uses_retry_after_and_missing_header_has_floor():
    sleeps = []
    http = client_with_responses([
        httpx.Response(429, headers={"Retry-After": "3"}),
        httpx.Response(429),
        httpx.Response(200, content=b"ok"),
    ], sleeps=sleeps)

    assert http.get_bytes("/", context=context()) == b"ok"
    assert sleeps == [3.0, 2.0]


def test_terminal_4xx_does_not_retry():
    sleeps = []
    http = client_with_responses([httpx.Response(401)], sleeps=sleeps)

    with pytest.raises(Exception) as exc:
        http.get_bytes("/", headers={"Authorization": "Bearer secret"}, context=context())

    assert sleeps == []
    assert getattr(exc.value, "failure_class") == FailureClass.TERMINAL


def test_response_limit_is_enforced():
    http = client_with_responses([httpx.Response(200, content=b"abcdef")], max_response_bytes=3)

    with pytest.raises(ResponseTooLarge):
        http.get_bytes("/", context=context())


def test_cancel_prevents_request():
    http = client_with_responses([httpx.Response(200, content=b"should-not-read")])

    with pytest.raises(Exception, match="cancelled"):
        http.get_bytes("/", context=context(), cancel=lambda: True)


def test_redacts_sensitive_headers():
    assert redact_headers({"Authorization": "Bearer abc", "Accept": "xml"}) == {
        "Authorization": "[redacted]",
        "Accept": "xml",
    }


def test_wfs_paging_stops_on_empty_feature_collection():
    http = client_with_responses([
        httpx.Response(200, content=b'{"features":[{"id":1}]}'),
        httpx.Response(200, content=b'{"features":[]}'),
    ])
    pages = list(WfsClient(http).pages(type_name="layer", context=context(), paging=WfsPagingConfig(page_size=10, max_pages=5)))

    assert [page.start_index for page in pages] == [0]


def test_wfs_page_limit_fails_loudly():
    http = client_with_responses([
        httpx.Response(200, content=b'{"features":[{"id":1}]}'),
        httpx.Response(200, content=b'{"features":[{"id":2}]}'),
    ])

    with pytest.raises(PageLimitExceeded):
        list(WfsClient(http).pages(type_name="layer", context=context(), paging=WfsPagingConfig(page_size=10, max_pages=2)))
