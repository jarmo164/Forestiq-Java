#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="${1:-${GITHUB_REPOSITORY:-jarmo164/ForestIQ}}"
mode="${2:-apply}"
evidence_dir="${3:-$ROOT/artifacts/governance}"

command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) is required." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required." >&2; exit 1; }

gh auth status >/dev/null
mkdir -p "$evidence_dir"

case "$mode" in
  apply)
    bash "$ROOT/scripts/configure-main-protection.sh" "$repo" apply
    RUNNER_REPO="$repo" bash "$ROOT/scripts/safe-auto-merge.sh" configure
    ;;
  verify)
    bash "$ROOT/scripts/configure-main-protection.sh" "$repo" verify
    RUNNER_REPO="$repo" bash "$ROOT/scripts/safe-auto-merge.sh" verify
    ;;
  *)
    echo "Usage: $0 [owner/repository] [apply|verify] [evidence-dir]" >&2
    exit 2
    ;;
esac

repo_json="$evidence_dir/repository.json"
protection_json="$evidence_dir/main-protection.json"
report="$evidence_dir/governance-audit.md"
json_report="$evidence_dir/governance-audit.json"

gh api "repos/$repo" > "$repo_json"
gh api "repos/$repo/branches/main/protection" > "$protection_json"

python3 "$ROOT/scripts/github_repository_governance_audit.py"   --repository-json "$repo_json"   --protection-json "$protection_json"   --report "$report"   --json-report "$json_report"

echo "ForestIQ governance evidence written to $evidence_dir"
