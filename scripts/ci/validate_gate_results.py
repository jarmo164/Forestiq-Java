#!/usr/bin/env python3
from __future__ import annotations

import argparse
from dataclasses import dataclass


@dataclass(frozen=True)
class GateResult:
    name: str
    selected: bool
    actual: str

    @property
    def expected(self) -> str:
        return "success" if self.selected else "skipped"


def parse_job(value: str) -> GateResult:
    parts=value.split(":",2)
    if len(parts)!=3:
        raise ValueError(f"invalid --job value: {value}")
    name, selected_raw, actual=parts
    if selected_raw not in {"true","false"}:
        raise ValueError(f"{name}: selected must be true/false, got {selected_raw!r}")
    return GateResult(name=name,selected=selected_raw=="true",actual=actual or "missing")


def evaluate(results: list[GateResult]) -> list[str]:
    failures=[]
    for result in results:
        if result.actual != result.expected:
            failures.append(
                f"{result.name}: expected={result.expected} actual={result.actual}"
            )
    return failures


def main() -> int:
    parser=argparse.ArgumentParser(description="Fail-closed ForestIQ aggregate gate decision.")
    parser.add_argument("--job",action="append",default=[],help="name:true|false:actual")
    parser.add_argument("--changed-files",default="")
    args=parser.parse_args()

    results=[parse_job(item) for item in args.job]
    print(f"changed_files={args.changed_files or '(unknown)'}")
    failures=evaluate(results)
    for result in results:
        print(f"{result.name}: selected={str(result.selected).lower()} expected={result.expected} actual={result.actual}")
    if failures:
        for failure in failures:
            print("FAIL "+failure)
        return 1
    print("PASS aggregate gate decision is consistent with classifier selection.")
    return 0


if __name__=="__main__":
    raise SystemExit(main())
