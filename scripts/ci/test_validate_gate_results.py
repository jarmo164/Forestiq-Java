import unittest
from scripts.ci.validate_gate_results import GateResult, evaluate


class AggregateGateDecisionTest(unittest.TestCase):
    def test_selected_success_passes(self):
        self.assertEqual(evaluate([GateResult("backend",True,"success")]),[])

    def test_selected_skipped_fails(self):
        self.assertEqual(
            evaluate([GateResult("backend",True,"skipped")]),
            ["backend: expected=success actual=skipped"],
        )

    def test_unselected_skipped_passes(self):
        self.assertEqual(evaluate([GateResult("backend",False,"skipped")]),[])

    def test_unselected_success_fails_closed(self):
        self.assertEqual(
            evaluate([GateResult("backend",False,"success")]),
            ["backend: expected=skipped actual=success"],
        )

    def test_failure_cancelled_timed_out_and_missing_fail(self):
        for actual in ("failure","cancelled","timed_out","missing",""):
            with self.subTest(actual=actual):
                value=actual or "missing"
                self.assertTrue(evaluate([GateResult("backend",True,value)]))


if __name__=="__main__":
    unittest.main()
