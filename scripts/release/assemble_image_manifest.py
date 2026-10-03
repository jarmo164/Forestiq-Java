#!/usr/bin/env python3
from __future__ import annotations
import argparse, json
from pathlib import Path

REQUIRED={"api","web","ingest"}

def assemble_manifest(commit:str,input_dir:Path,release_id:str|None=None)->dict:
    records={}
    for path in sorted(input_dir.glob("*.json")):
        if path.name.endswith("-trivy.json"): continue
        data=json.loads(path.read_text(encoding="utf-8"))
        if data.get("image") in REQUIRED: records[data["image"]]=data
    missing=sorted(REQUIRED-records.keys())
    if missing: raise ValueError(f"missing image records: {missing}")
    for key,record in records.items():
        if record.get("commit")!=commit: raise ValueError(f"{key}: commit mismatch")
        if not str(record.get("digest","")).startswith("sha256:"): raise ValueError(f"{key}: invalid digest")
    resolved_release_id=release_id or f"sha-{commit[:12]}"
    if not resolved_release_id.strip(): raise ValueError("release id is required")
    return {
        "schemaVersion":1,
        "releaseId":resolved_release_id,
        "commit":commit,
        "images":{key:records[key] for key in sorted(REQUIRED)},
    }

def main()->int:
    parser=argparse.ArgumentParser()
    parser.add_argument("--commit",required=True)
    parser.add_argument("--release-id",required=False)
    parser.add_argument("--input",required=True)
    parser.add_argument("--output",required=True)
    args=parser.parse_args()
    payload=assemble_manifest(args.commit,Path(args.input),args.release_id)
    output=Path(args.output)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(payload,indent=2)+"\n",encoding="utf-8")
    print(f"release_id={payload['releaseId']}")
    return 0

if __name__=="__main__": raise SystemExit(main())
