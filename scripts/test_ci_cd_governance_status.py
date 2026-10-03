import json
import unittest
from pathlib import Path
from scripts.validate_ci_cd_governance_status import validate

ROOT=Path(__file__).resolve().parents[1]

class GovernanceStatusTest(unittest.TestCase):
    def test_current_status_file_is_valid(self):
        data=json.loads((ROOT/"config"/"ci-cd-governance-status.json").read_text(encoding="utf-8"))
        self.assertEqual(validate(data),[])

    def test_live_verified_requires_evidence(self):
        data={"status_order":["code_merged","configured","live_verified"],"issues":[{"issue":1,"state":"open","status":"live_verified","codeEvidence":["x"],"liveEvidence":None,"blocking":"x"}]}
        self.assertTrue(any("live_verified requires liveEvidence" in item for item in validate(data)))

    def test_open_issue_requires_blocking_reason(self):
        data={"status_order":["code_merged","configured","live_verified"],"issues":[{"issue":1,"state":"open","status":"code_merged","codeEvidence":["x"],"liveEvidence":None,"blocking":None}]}
        self.assertTrue(any("open issue must explain" in item for item in validate(data)))

if __name__=="__main__":
    unittest.main()
