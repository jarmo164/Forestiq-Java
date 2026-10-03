import unittest
from scripts.build_rik_metsis_cutover_evidence import build

class RikMetsisEvidenceTest(unittest.TestCase):
    def ready(self):
        etl=lambda key: {"sourceKey":key,"classification":"ETL_ONLY","parityReady":True,"successfulPythonRuns":2,"rollbackVerified":True,"providerFailureTestsPassed":True}
        return {"sources":[
          etl("metsis_owner_portfolio"),etl("official_inheritance_notices"),etl("weasel_ownership_transitions"),
          {"sourceKey":"rik_cadastre_count","classification":"REAL_TIME_DOMAIN_OPERATION","exceptionReason":"request-time SOAP domain lookup","authorizationBound":True,"schedulerDisabled":True,"apiCredentialOwned":True}
        ]}
    def test_ready_bundle(self):
        result=build(self.ready(),"abcdef123456")
        self.assertEqual(result["status"],"READY"); self.assertEqual(result["successfulPythonRuns"],2)
    def test_rik_exception_must_be_authorization_bound(self):
        raw=self.ready(); raw["sources"][-1]["authorizationBound"]=False
        result=build(raw,"abcdef123456")
        self.assertEqual(result["status"],"PENDING")
        self.assertIn("rik_cadastre_count:realtime exception incomplete",result["failures"])
if __name__=="__main__": unittest.main()
