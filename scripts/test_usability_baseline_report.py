import csv
import tempfile
import unittest
from pathlib import Path

from scripts.usability_baseline_report import load, report


FIELDS = [
    "session", "role", "scenario", "variant", "success", "seconds",
    "help", "errors", "recovery", "confidence", "observation",
]


class UsabilityBaselineReportTest(unittest.TestCase):
    def write_rows(self, rows, fields=FIELDS):
        temp = tempfile.TemporaryDirectory()
        path = Path(temp.name) / "sessions.csv"
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        self.addCleanup(temp.cleanup)
        return path

    def test_sample_gate_requires_five_sessions_and_three_roles(self):
        rows = []
        roles = ["CALLER", "EVALUATOR", "SALES_MANAGER", "CALLER", "ORG_ADMIN"]
        for index, role in enumerate(roles, start=1):
            rows.append({
                "session": f"S{index:02d}",
                "role": role,
                "scenario": "A",
                "variant": "current",
                "success": "completed",
                "seconds": str(20 + index),
                "help": "none",
                "errors": "0",
                "recovery": "self",
                "confidence": "4",
                "observation": "anonymous note",
            })
        text, gate = report(load(self.write_rows(rows)))
        self.assertTrue(gate)
        self.assertIn("Sessions: **5**", text)
        self.assertIn("Role profiles: **4**", text)

    def test_rejects_identifying_columns(self):
        fields = FIELDS + ["email"]
        row = {field: "" for field in fields}
        row.update({
            "session": "S01",
            "role": "CALLER",
            "scenario": "A",
            "variant": "current",
            "success": "completed",
            "seconds": "10",
            "help": "none",
            "errors": "0",
            "recovery": "self",
            "confidence": "5",
            "observation": "anonymous",
            "email": "do-not-store@example.invalid",
        })
        with self.assertRaisesRegex(ValueError, "identifiers"):
            load(self.write_rows([row], fields=fields))

    def test_incomplete_sample_is_reported_without_faking_completion(self):
        row = {
            "session": "S01",
            "role": "CALLER",
            "scenario": "A",
            "variant": "current",
            "success": "completed",
            "seconds": "10",
            "help": "none",
            "errors": "0",
            "recovery": "self",
            "confidence": "5",
            "observation": "anonymous",
        }
        text, gate = report(load(self.write_rows([row])))
        self.assertFalse(gate)
        self.assertIn("INCOMPLETE", text)


if __name__ == "__main__":
    unittest.main()
