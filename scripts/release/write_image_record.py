#!/usr/bin/env python3
from __future__ import annotations
import argparse, json
from pathlib import Path

def main() -> int:
    parser=argparse.ArgumentParser()
    parser.add_argument("--image",required=True); parser.add_argument("--repository",required=True)
    parser.add_argument("--digest",required=True); parser.add_argument("--commit",required=True); parser.add_argument("--output",required=True)
    args=parser.parse_args()
    if not args.digest.startswith("sha256:") or len(args.digest)!=71: raise SystemExit("digest must be a sha256 OCI digest")
    if len(args.commit)!=40: raise SystemExit("commit must be a full 40-character SHA")
    payload={"image":args.image,"repository":args.repository,"digest":args.digest,"reference":f"{args.repository}@{args.digest}","commit":args.commit}
    output=Path(args.output); output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(payload,indent=2)+"\n",encoding="utf-8")
    return 0
if __name__=="__main__": raise SystemExit(main())
