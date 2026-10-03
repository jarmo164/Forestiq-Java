from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Mapping

import httpx

from forestiq_ingest.http.retry import FailureClass, RetryPolicy, classify_http_status


SENSITIVE_HEADERS = {"authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie"}


class HttpSourceError(RuntimeError):
    def __init__(self, message: str, failure_class: FailureClass):
        super().__init__(message)
        self.failure_class = failure_class


class ResponseTooLarge(HttpSourceError):
    pass


@dataclass(frozen=True)
class HttpRequestContext:
    run_id: str
    job_key: str
    source: str
    batch_no: int = 0
    source_cursor: str | None = None


@dataclass(frozen=True)
class HttpClientConfig:
    connect_timeout_seconds: float = 10.0
    read_timeout_seconds: float = 60.0
    write_timeout_seconds: float = 30.0
    pool_timeout_seconds: float = 10.0
    max_response_bytes: int = 25_000_000


class HttpSourceClient:
    def __init__(
        self,
        *,
        base_url: str,
        config: HttpClientConfig | None = None,
        retry_policy: RetryPolicy | None = None,
        client: httpx.Client | None = None,
        logger: logging.Logger | None = None,
        sleep=time.sleep,
    ):
        self.base_url = base_url.rstrip("/")
        self.config = config or HttpClientConfig()
        self.retry_policy = retry_policy or RetryPolicy()
        timeout = httpx.Timeout(
            connect=self.config.connect_timeout_seconds,
            read=self.config.read_timeout_seconds,
            write=self.config.write_timeout_seconds,
            pool=self.config.pool_timeout_seconds,
        )
        self.client = client or httpx.Client(base_url=self.base_url, timeout=timeout)
        self.logger = logger or logging.getLogger("forestiq_ingest.http")
        self.sleep = sleep

    def get_bytes(
        self,
        path: str,
        *,
        params: Mapping[str, object] | None = None,
        headers: Mapping[str, str] | None = None,
        context: HttpRequestContext,
        cancel=None,
    ) -> bytes:
        safe_headers = redact_headers(headers or {})
        for attempt in range(1, self.retry_policy.max_attempts + 1):
            if cancel is not None and cancel():
                raise HttpSourceError("HTTP request cancelled", FailureClass.TERMINAL)

            started = time.monotonic()
            try:
                response = self.client.get(path, params=params, headers=headers)
            except (httpx.TimeoutException, httpx.NetworkError, httpx.TransportError) as exc:
                if attempt >= self.retry_policy.max_attempts:
                    raise HttpSourceError(_safe_message(exc), FailureClass.RETRYABLE) from exc
                self._log(context, attempt, "retryable_network_error", started)
                self.sleep(self.retry_policy.delay_for_attempt(attempt))
                continue

            failure_class = classify_http_status(response.status_code)
            if failure_class == FailureClass.SUCCESS:
                body = self._bounded_body(response)
                self._log(context, attempt, "success", started)
                return body

            if failure_class == FailureClass.TERMINAL:
                response.close()
                self._log(context, attempt, f"terminal_http_{response.status_code}", started, safe_headers)
                raise HttpSourceError(f"terminal HTTP status {response.status_code}", FailureClass.TERMINAL)

            retry_after = response.headers.get("Retry-After") if failure_class == FailureClass.RATE_LIMITED else None
            response.close()
            if attempt >= self.retry_policy.max_attempts:
                raise HttpSourceError(f"retry budget exhausted after HTTP {response.status_code}", failure_class)

            self._log(context, attempt, f"retry_http_{response.status_code}", started, safe_headers)
            self.sleep(self.retry_policy.delay_for_attempt(attempt, retry_after=retry_after))

        raise HttpSourceError("retry budget exhausted", FailureClass.RETRYABLE)

    def stream_bytes(
        self,
        path: str,
        *,
        params: Mapping[str, object] | None = None,
        headers: Mapping[str, str] | None = None,
        context: HttpRequestContext,
        cancel=None,
    ):
        body = self.get_bytes(path, params=params, headers=headers, context=context, cancel=cancel)
        yield body

    def _bounded_body(self, response: httpx.Response) -> bytes:
        chunks: list[bytes] = []
        total = 0
        for chunk in response.iter_bytes():
            total += len(chunk)
            if total > self.config.max_response_bytes:
                response.close()
                raise ResponseTooLarge("response body exceeded configured byte limit", FailureClass.TERMINAL)
            chunks.append(chunk)
        return b"".join(chunks)

    def _log(self, context: HttpRequestContext, attempt: int, outcome: str, started: float, headers=None) -> None:
        duration_ms = int((time.monotonic() - started) * 1000)
        self.logger.info(
            "source_http_request",
            extra={
                "run_id": context.run_id,
                "job_key": context.job_key,
                "source": context.source,
                "batch_no": context.batch_no,
                "source_cursor": context.source_cursor or "",
                "attempt": attempt,
                "duration_ms": duration_ms,
                "outcome": outcome,
                "headers": headers or {},
            },
        )


def redact_headers(headers: Mapping[str, str]) -> dict[str, str]:
    return {
        key: "[redacted]" if key.lower() in SENSITIVE_HEADERS else value
        for key, value in headers.items()
    }


def _safe_message(exception: Exception) -> str:
    message = str(exception) or exception.__class__.__name__
    for token in ("authorization", "token", "password", "secret"):
        message = message.replace(token, "[redacted]")
    return " ".join(message.split())[:500]
