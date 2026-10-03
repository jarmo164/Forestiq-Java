"""Shared HTTP and WFS client toolkit for ingestion adapters."""

from .client import HttpRequestContext, HttpSourceClient
from .retry import FailureClass, RetryPolicy, classify_http_status

__all__ = ["FailureClass", "HttpRequestContext", "HttpSourceClient", "RetryPolicy", "classify_http_status"]
