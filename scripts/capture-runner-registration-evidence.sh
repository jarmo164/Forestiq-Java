#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="${1:-${GITHUB_REPOSITORY:-jarmo164/ForestIQ}}"
evidence_dir="${2:-$ROOT/artifacts/runner-registration}"
runner_name="${RUNNER_NAME:-koodikratt}"

command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) is required." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required." >&2; exit 1; }

gh auth status >/dev/null
mkdir -p "$evidence_dir"

runner_json="$evidence_dir/repository-runners.json"
report="$evidence_dir/runner-registration.md"

gh api "repos/$repo/actions/runners" > "$runner_json"

python3 "$ROOT/scripts/github_runner_registration_audit.py" "$runner_json"   --runner-name "$runner_name"   --report "$report"

echo "ForestIQ runner registration evidence written to $evidence_dir"
