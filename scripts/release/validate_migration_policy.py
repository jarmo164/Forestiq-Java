#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
DEFAULT_POLICY=ROOT/"config"/"migration-policy.json"
DEFAULT_DIR=ROOT/"backend"/"src"/"main"/"resources"/"db"/"migration"
VERSION_RE=re.compile(r"^V(?P<version>\d+(?:_\d+)*)__.*\.sql$")

def version_tuple(value:str)->tuple[int,...]:
    return tuple(int(part) for part in value.split("_"))

def validate(migration_dir:Path,policy:dict)->list[str]:
    failures=[]
    enforce_from=version_tuple(str(policy["enforceFromVersion"]))
    marker=str(policy["exceptionMarker"]).lower()
    patterns=[re.compile(item,re.I|re.S) for item in policy["forbiddenPatterns"]]
    seen={}
    for path in sorted(migration_dir.glob("V*.sql")):
        match=VERSION_RE.match(path.name)
        if not match:
            failures.append(f"{path.name}: invalid versioned migration filename")
            continue
        version=match.group("version")
        if version in seen:
            failures.append(f"duplicate migration version {version}: {seen[version]} and {path.name}")
        seen[version]=path.name
        if version_tuple(version)<enforce_from: continue
        text=path.read_text(encoding="utf-8")
        destructive=[pattern.pattern for pattern in patterns if pattern.search(text)]
        if destructive and marker not in text.lower():
            failures.append(f"{path.name}: destructive contract-phase SQL requires '{policy['exceptionMarker']}' with a reviewed compatibility reason")
    return failures

def main()->int:
    parser=argparse.ArgumentParser()
    parser.add_argument("--migration-dir",default=str(DEFAULT_DIR))
    parser.add_argument("--policy",default=str(DEFAULT_POLICY))
    args=parser.parse_args()
    policy=json.loads(Path(args.policy).read_text(encoding="utf-8"))
    failures=validate(Path(args.migration_dir),policy)
    if failures:
        for failure in failures: print("FAIL "+failure)
        return 1
    print("Flyway expand/migrate/contract policy is valid.")
    return 0
if __name__=="__main__": raise SystemExit(main())
