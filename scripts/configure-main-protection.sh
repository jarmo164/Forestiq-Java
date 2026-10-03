#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
policy="${FORESTIQ_REPOSITORY_POLICY:-$ROOT/.github/forestiq-repository-policy.json}"
repo="${1:-${GITHUB_REPOSITORY:-jarmo164/ForestIQ}}"
mode="${2:-apply}"

command -v gh >/dev/null 2>&1 || { echo "GitHub CLI (gh) is required." >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required." >&2; exit 1; }
[[ -f "$policy" ]] || { echo "Repository policy not found: $policy" >&2; exit 1; }

gh auth status >/dev/null

branch="$(jq -r '.branch' "$policy")"

verify_protection() {
  local actual expected_checks actual_checks

  actual="$(gh api     -H "Accept: application/vnd.github+json"     -H "X-GitHub-Api-Version: 2022-11-28"     "repos/${repo}/branches/${branch}/protection")"

  expected_checks="$(jq -c '.required_status_checks.contexts | sort' "$policy")"
  actual_checks="$(jq -c '.required_status_checks.contexts | sort' <<<"$actual")"

  [[ "$actual_checks" == "$expected_checks" ]] || {
    echo "Required status checks differ from $policy." >&2
    echo "expected: $expected_checks" >&2
    echo "actual:   $actual_checks" >&2
    return 1
  }

  jq -e --slurpfile policy "$policy" '
    .required_status_checks.strict == $policy[0].required_status_checks.strict and
    .enforce_admins.enabled == $policy[0].enforce_admins and
    .required_pull_request_reviews.required_approving_review_count == $policy[0].pull_request.required_approving_review_count and
    .required_conversation_resolution.enabled == $policy[0].required_conversation_resolution and
    .allow_force_pushes.enabled == $policy[0].allow_force_pushes and
    .allow_deletions.enabled == $policy[0].allow_deletions
  ' <<<"$actual" >/dev/null || {
    echo "Branch protection does not match $policy." >&2
    return 1
  }

  echo "ForestIQ ${branch} protection matches $policy."
}

apply_protection() {
  local payload
  payload="$(jq '{
    required_status_checks: .required_status_checks,
    enforce_admins: .enforce_admins,
    required_pull_request_reviews: {
      dismiss_stale_reviews: .pull_request.dismiss_stale_reviews,
      require_code_owner_reviews: .pull_request.require_code_owner_reviews,
      required_approving_review_count: .pull_request.required_approving_review_count,
      require_last_push_approval: .pull_request.require_last_push_approval
    },
    restrictions: null,
    required_linear_history: false,
    allow_force_pushes: .allow_force_pushes,
    allow_deletions: .allow_deletions,
    block_creations: false,
    required_conversation_resolution: .required_conversation_resolution,
    lock_branch: false,
    allow_fork_syncing: false
  }' "$policy")"

  printf '%s\n' "$payload" | gh api     --method PUT     -H "Accept: application/vnd.github+json"     -H "X-GitHub-Api-Version: 2022-11-28"     "repos/${repo}/branches/${branch}/protection"     --input - >/dev/null
}

case "$mode" in
  apply)
    echo "Applying repository policy to ${repo}:${branch} ..."
    apply_protection
    verify_protection
    ;;
  verify)
    verify_protection
    ;;
  *)
    echo "Usage: $0 [owner/repository] [apply|verify]" >&2
    exit 2
    ;;
esac
