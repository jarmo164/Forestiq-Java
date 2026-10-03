import csv
import tempfile
import unittest
from pathlib import Path

from scripts.production_performance_report import load, report


class ProductionPerformanceReportTest(unittest.TestCase):
    def write_csv(self, fieldnames, row):
        temp = tempfile.TemporaryDirectory()
        path = Path(temp.name) / "evidence.csv"
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerow(row)
        return temp, path

    def test_renders_aggregate_baseline(self):
        fields = [
            "window_start", "workflow", "sample_count", "p50_ms", "p95_ms",
            "p99_ms", "error_rate_percent", "db_p95_ms"
        ]
        temp, path = self.write_csv(fields, {
            "window_start": "2026-09-21T09:00:00Z",
            "workflow": "parcel-detail",
            "sample_count": "1200",
            "p50_ms": "80",
            "p95_ms": "220",
            "p99_ms": "410",
            "error_rate_percent": "0.2",
            "db_p95_ms": "90",
        })
        self.addCleanup(temp.cleanup)
        text = report(load(path))
        self.assertIn("parcel-detail", text)
        self.assertIn("1200", text)
        self.assertIn("220", text)

    def test_rejects_sensitive_raw_columns(self):
        fields = [
            "window_start", "workflow", "sample_count", "p50_ms", "p95_ms",
            "p99_ms", "error_rate_percent", "authorization_token"
        ]
        temp, path = self.write_csv(fields, {
            "window_start": "2026-09-21T09:00:00Z",
            "workflow": "owners",
            "sample_count": "10",
            "p50_ms": "10",
            "p95_ms": "20",
            "p99_ms": "30",
            "error_rate_percent": "0",
            "authorization_token": "forbidden",
        })
        self.addCleanup(temp.cleanup)
        with self.assertRaisesRegex(ValueError, "aggregate-only"):
            load(path)

    def test_rejects_invalid_percentile_order(self):
        fields = [
            "window_start", "workflow", "sample_count", "p50_ms", "p95_ms",
            "p99_ms", "error_rate_percent"
        ]
        temp, path = self.write_csv(fields, {
            "window_start": "2026-09-21T09:00:00Z",
            "workflow": "map-tiles",
            "sample_count": "10",
            "p50_ms": "50",
            "p95_ms": "40",
            "p99_ms": "60",
            "error_rate_percent": "0",
        })
        self.addCleanup(temp.cleanup)
        with self.assertRaisesRegex(ValueError, "p50_ms <= p95_ms <= p99_ms"):
            load(path)


if __name__ == "__main__":
    unittest.main()
