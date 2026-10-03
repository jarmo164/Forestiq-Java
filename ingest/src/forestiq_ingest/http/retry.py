from __future__ import annotations

import random
import time
from dataclasses import dataclass
from enum import StrEnum
from email.utils import parsedate_to_datetime


class FailureClass(StrEnum):
    SUCCESS = "SUCCESS"
    RETRYABLE = "RETRYABLE"
    RATE_LIMITED = "RATE_LIMITED"
    TERMINAL = "TERMINAL"


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int = 4
    base_delay_seconds: float = 0.5
    max_delay_seconds: float = 30.0
    jitter_ratio: float = 0.2
    min_rate_limit_delay_seconds: float = 1.0

    def delay_for_attempt(self, attempt: int, retry_after: str | None = None) -> float:
        if retry_after:
            parsed = parse_retry_after(retry_after)
            if parsed is not None:
                return max(self.min_rate_limit_delay_seconds, min(parsed, self.max_delay_seconds))

        exponent = max(0, attempt - 1)
        delay = min(self.max_delay_seconds, self.base_delay_seconds * (2 ** exponent))
        jitter = delay * self.jitter_ratio
        return max(0.0, delay + random.uniform(-jitter, jitter))


def classify_http_status(status_code: int) -> FailureClass:
    if 200 <= status_code < 300:
        return FailureClass.SUCCESS
    if status_code == 429:
        return FailureClass.RATE_LIMITED
    if status_code in {408, 425} or 500 <= status_code < 600:
        return FailureClass.RETRYABLE
    return FailureClass.TERMINAL


def parse_retry_after(value: str | None) -> float | None:
    if not value:
        return None
    stripped = value.strip()
    try:
        return max(0.0, float(stripped))
    except ValueError:
        pass

    try:
        target = parsedate_to_datetime(stripped)
        return max(0.0, target.timestamp() - time.time())
    except (TypeError, ValueError, OverflowError):
        return None
