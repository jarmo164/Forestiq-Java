#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_POLICY = ROOT / "config" / "self-hosted-runner-security.json"


def load_policy(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("security policy must be a JSON object")
    return value


def evaluate(policy: dict, environment_names: set[str]) -> list[str]:
    forbidden = set(policy.get("forbidden_environment_names") or [])
    return sorted(forbidden & environment_names)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Check self-hosted CI environment variable names for production/provider secret leakage."
    )
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    args = parser.parse_args()

    leaked = evaluate(load_policy(args.policy), set(os.environ))
    if leaked:
        print("FAIL forbidden credential environment names are present: " + ", ".join(leaked))
        return 1

    print("PASS no forbidden production/provider credential environment names are present.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
