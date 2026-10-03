#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="${1:-${GITHUB_REPOSITORY:-jarmo164/ForestIQ}}"
evidence_dir="${2:-$ROOT/artifacts/main-protection}"
branch="$(python3 -c 'import json; print(json.load(open(".github/forestiq-repository-policy.json"))["branch"])')"

command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) is required." >&2; exit 1; }
gh auth status >/dev/null
mkdir -p "$evidence_dir"

raw="$evidence_dir/main-protection.json"
gh api "repos/$repo/branches/$branch/protection" > "$raw"

python3 "$ROOT/scripts/github_branch_protection_audit.py" "$raw"   --report "$evidence_dir/main-protection.md"   --json-report "$evidence_dir/main-protection-audit.json"

echo "Main protection evidence written to $evidence_dir"
