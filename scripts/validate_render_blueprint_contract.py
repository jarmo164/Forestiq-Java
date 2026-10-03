#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BLUEPRINT = ROOT / "render.yaml"
CONTRACT = ROOT / "config" / "render-env-contract.json"


def service_blocks(text: str) -> dict[str, str]:
    matches=list(re.finditer(r"^  - type:\s+\w+\n    name:\s+([^\n]+)\n", text, re.M))
    result={}
    for index, match in enumerate(matches):
        end=matches[index+1].start() if index+1 < len(matches) else text.find("\ndatabases:")
        if end < 0:
            end=len(text)
        result[match.group(1).strip()]=text[match.start():end]
    return result


def env_blocks(service: str) -> dict[str, str]:
    matches=list(re.finditer(r"^      - key:\s+([A-Z0-9_]+)\s*$", service, re.M))
    result={}
    for index, match in enumerate(matches):
        end=matches[index+1].start() if index+1 < len(matches) else len(service)
        result[match.group(1)]=service[match.start():end]
    return result


def evaluate(text: str, contract: dict) -> list[str]:
    failures=[]
    services=service_blocks(text)
    db_names=set(re.findall(r"^  - name:\s+([A-Za-z0-9_-]+)\s*$", text.split("\ndatabases:",1)[1] if "\ndatabases:" in text else "", re.M))
    service_names=set(services)

    expected_services=set(contract["services"])
    if service_names != expected_services:
        failures.append(f"services={sorted(service_names)}, expected={sorted(expected_services)}")

    for name, spec in contract["services"].items():
        block=services.get(name,"")
        env=env_blocks(block)
        actual=set(env)
        required=set(spec["required_env"])
        missing=sorted(required-actual)
        stale=sorted(actual-required)
        if missing:
            failures.append(f"{name}: missing env {missing}")
        if stale:
            failures.append(f"{name}: stale/undocumented env {stale}")
        for key in spec.get("secret_env",[]):
            item=env.get(key,"")
            safe=("sync: false" in item or "fromService:" in item or "fromDatabase:" in item or "generateValue: true" in item)
            if not safe:
                failures.append(f"{name}: secret {key} is hardcoded or unmanaged")

    for target in re.findall(r"fromService:\n\s+type:\s+\w+\n\s+name:\s+([^\n]+)", text):
        if target.strip() not in service_names:
            failures.append(f"unknown fromService target {target.strip()}")
    for target in re.findall(r"fromDatabase:\n\s+name:\s+([^\n]+)", text):
        if target.strip() not in db_names:
            failures.append(f"unknown fromDatabase target {target.strip()}")

    if contract["previews"]["generation"] == "off" and not re.search(r"^previews:\n\s+generation:\s+off\s*$", text, re.M):
        failures.append("preview generation must remain off")
    if "autoDeployTrigger: commit" in text:
        failures.append("uncontrolled commit auto-deploy is forbidden")

    api=services.get("forestiq-api","")
    keycloak=services.get("forestiq-keycloak","")
    if "      - key: DATABASE_USERNAME\n        sync: false" not in api or "      - key: DATABASE_PASSWORD\n        sync: false" not in api:
        failures.append("forestiq-api: runtime DB login must be Render-managed and separate from database owner")
    if "      - key: DATABASE_URL\n        sync: false" not in api:
        failures.append("forestiq-api: JDBC URL must not expose Render database-owner connectionString")
    if '      - key: SPRING_FLYWAY_ENABLED\n        value: "false"' not in api:
        failures.append("forestiq-api: runtime Flyway must be disabled; release workflow owns migrations")
    if "      - key: KC_DB_USERNAME\n        sync: false" not in keycloak or "      - key: KC_DB_PASSWORD\n        sync: false" not in keycloak:
        failures.append("forestiq-keycloak: dedicated DB login is required")
    if "      - key: KC_DB_SCHEMA\n        value: keycloak" not in keycloak:
        failures.append("forestiq-keycloak: dedicated keycloak schema is required")

    return failures


def main() -> int:
    contract=json.loads(CONTRACT.read_text(encoding="utf-8"))
    failures=evaluate(BLUEPRINT.read_text(encoding="utf-8"),contract)
    if failures:
        for failure in failures:
            print("FAIL "+failure)
        return 1
    print("Render Blueprint environment contract is valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
