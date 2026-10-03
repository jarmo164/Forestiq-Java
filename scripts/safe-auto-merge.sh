#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
policy="${FORESTIQ_REPOSITORY_POLICY:-$ROOT/.github/forestiq-repository-policy.json}"
repo="${RUNNER_REPO:-${GITHUB_REPOSITORY:-jarmo164/ForestIQ}}"
command_name="${1:-}"
pr_number="${2:-}"

command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) is required." >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required." >&2; exit 1; }
[[ -f "$policy" ]] || { echo "Repository policy not found: $policy" >&2; exit 1; }

gh auth status >/dev/null
branch="$(jq -r '.branch' "$policy")"

verify_protection() {
  bash "$ROOT/scripts/configure-main-protection.sh" "$repo" verify >/dev/null
}

verify_merge_settings() {
  local actual
  actual="$(gh api "repos/${repo}")"
  jq -e --slurpfile policy "$policy" '
    .allow_auto_merge == $policy[0].merge.allow_auto_merge and
    .allow_squash_merge == $policy[0].merge.allow_squash_merge and
    .allow_merge_commit == $policy[0].merge.allow_merge_commit and
    .allow_rebase_merge == $policy[0].merge.allow_rebase_merge
  ' <<<"$actual" >/dev/null || {
    echo "Repository merge settings do not match $policy." >&2
    return 1
  }
}

case "$command_name" in
  configure)
    verify_protection
    jq '.merge' "$policy" | gh api --method PATCH "repos/${repo}" --input - >/dev/null
    verify_merge_settings
    echo "Repository merge policy configured from $policy."
    ;;

  pr)
    [[ "$pr_number" =~ ^[0-9]+$ ]] || {
      echo "Usage: $0 pr <pull-request-number>" >&2
      exit 2
    }

    verify_protection
    verify_merge_settings

    pr_json="$(gh pr view "$pr_number" --repo "$repo" --json number,state,isDraft,baseRefName,body,url)"
    jq -e --arg branch "$branch" '
      .state == "OPEN" and
      .isDraft == false and
      .baseRefName == $branch
    ' <<<"$pr_json" >/dev/null || {
      echo "PR must be open, non-draft and target $branch." >&2
      exit 1
    }

    body="$(jq -r '.body // ""' <<<"$pr_json")"
    grep -Eq '#[0-9]+' <<<"$body" || {
      echo "PR body must reference the originating GitHub issue." >&2
      exit 1
    }

    [[ "$(jq -r '.merge.allow_squash_merge' "$policy")" == "true" ]] || {
      echo "ForestIQ policy does not allow squash merge." >&2
      exit 1
    }

    gh pr merge "$pr_number" --repo "$repo" --auto --squash
    ;;

  verify)
    verify_protection
    verify_merge_settings
    echo "ForestIQ repository policy is active."
    ;;

  *)
    cat >&2 <<EOF
Usage:
  $0 configure
  $0 verify
  $0 pr <pull-request-number>

Policy source: $policy
The helper never uses --admin and never bypasses required checks.
EOF
    exit 2
    ;;
esac
