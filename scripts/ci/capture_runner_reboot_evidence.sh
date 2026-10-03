#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
repo="${RUNNER_REPO:-${GITHUB_REPOSITORY:-jarmo164/ForestIQ}}"
before_run="${1:-}"
after_run="${2:-}"
evidence_dir="${3:-$ROOT/artifacts/runner-reboot}"

[[ "$before_run" =~ ^[0-9]+$ && "$after_run" =~ ^[0-9]+$ ]] || {
  echo "Usage: $0 <before-run-id> <after-run-id> [evidence-dir]" >&2
  exit 2
}

command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) is required." >&2; exit 1; }
gh auth status >/dev/null

rm -rf "$evidence_dir/before" "$evidence_dir/after"
mkdir -p "$evidence_dir/before" "$evidence_dir/after"

gh run download "$before_run" --repo "$repo" --dir "$evidence_dir/before"
gh run download "$after_run" --repo "$repo" --dir "$evidence_dir/after"

before_json="$(find "$evidence_dir/before" -type f -name acceptance.json -print -quit)"
after_json="$(find "$evidence_dir/after" -type f -name acceptance.json -print -quit)"

[[ -n "$before_json" ]] || { echo "before acceptance.json not found" >&2; exit 1; }
[[ -n "$after_json" ]] || { echo "after acceptance.json not found" >&2; exit 1; }

python3 "$ROOT/scripts/ci/verify_runner_reboot.py" "$before_json" "$after_json"   --report "$evidence_dir/reboot-evidence.md"   --json-report "$evidence_dir/reboot-evidence.json"

echo "Runner reboot evidence written to $evidence_dir"
