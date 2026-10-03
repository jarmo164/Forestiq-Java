#!/usr/bin/env python3
from __future__ import annotations
import argparse, json
from pathlib import Path

REQUIRED_ETL = {"metsis_owner_portfolio", "official_inheritance_notices", "weasel_ownership_transitions"}
REQUIRED_REALTIME = {"rik_cadastre_count"}

def load(path: Path) -> dict:
    value=json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value,dict): raise ValueError("input must be a JSON object")
    return value

def build(raw: dict, repository_sha: str) -> dict:
    entries=raw.get("sources")
    if not isinstance(entries,list): raise ValueError("sources must be a list")
    failures=[]; seen=set(); normalized=[]; etl_runs=[]
    for item in entries:
        key=str(item.get("sourceKey","")).strip(); seen.add(key)
        classification=item.get("classification")
        row={"sourceKey":key,"classification":classification}
        if key in REQUIRED_ETL:
            parity=item.get("parityReady") is True
            runs=int(item.get("successfulPythonRuns",0))
            rollback=item.get("rollbackVerified") is True
            provider_tests=item.get("providerFailureTestsPassed") is True
            passed=classification=="ETL_ONLY" and parity and runs>=2 and rollback and provider_tests
            if not passed: failures.append(f"{key}:etl evidence incomplete")
            etl_runs.append(runs)
            row|={"parityReady":parity,"successfulPythonRuns":runs,"rollbackVerified":rollback,"providerFailureTestsPassed":provider_tests,"passed":passed}
        elif key in REQUIRED_REALTIME:
            exception_reason=str(item.get("exceptionReason","")).strip()
            authorization=item.get("authorizationBound") is True
            scheduler_disabled=item.get("schedulerDisabled") is True
            api_credential=item.get("apiCredentialOwned") is True
            passed=classification=="REAL_TIME_DOMAIN_OPERATION" and bool(exception_reason) and authorization and scheduler_disabled and api_credential
            if not passed: failures.append(f"{key}:realtime exception incomplete")
            row|={"exceptionReason":exception_reason,"authorizationBound":authorization,"schedulerDisabled":scheduler_disabled,"apiCredentialOwned":api_credential,"passed":passed}
        else:
            failures.append(f"{key or '<missing>'}:unexpected source")
            row["passed"]=False
        normalized.append(row)
    for missing in sorted((REQUIRED_ETL|REQUIRED_REALTIME)-seen): failures.append(f"{missing}:missing")
    ready=not failures
    return {
      "source":"rik_metsis","issue":372,"disposition":"MIXED",
      "status":"READY" if ready else "PENDING","repositorySha":repository_sha,
      "parityReady":ready,"successfulPythonRuns":min(etl_runs) if len(etl_runs)==3 else 0,
      "rollbackVerified": all(x.get("rollbackVerified") is True for x in normalized if x.get("sourceKey") in REQUIRED_ETL) and len(etl_runs)==3,
      "authorizationBound": next((x.get("authorizationBound") for x in normalized if x.get("sourceKey")=="rik_cadastre_count"),False),
      "failures":failures,"sources":normalized,"notes":str(raw.get("notes",""))
    }

def main()->int:
    p=argparse.ArgumentParser(description="Build sanitized RIK/MetsIS cutover evidence for #372.")
    p.add_argument("input",type=Path); p.add_argument("--repository-sha",required=True); p.add_argument("--output",type=Path,required=True); p.add_argument("--require-ready",action="store_true")
    a=p.parse_args(); evidence=build(load(a.input),a.repository_sha)
    a.output.parent.mkdir(parents=True,exist_ok=True); a.output.write_text(json.dumps(evidence,indent=2,sort_keys=True)+"\n",encoding="utf-8"); print(a.output)
    if a.require_ready and evidence["status"]!="READY":
        for failure in evidence["failures"]: print("FAIL "+failure)
        return 1
    return 0
if __name__=="__main__": raise SystemExit(main())
