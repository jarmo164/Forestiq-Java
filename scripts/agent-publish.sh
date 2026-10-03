#!/usr/bin/env bash
set -euo pipefail

ISSUE="${1:-}"
if [[ -z "$ISSUE" || ! "$ISSUE" =~ ^[0-9]+$ ]]; then
  echo "Usage: scripts/agent-publish.sh <issue-number>" >&2
  exit 2
fi

for cmd in git gh just; do
  command -v "$cmd" >/dev/null || { echo "Missing required command: $cmd" >&2; exit 1; }
done

ROOT="$(git rev-parse --show-toplevel)"
REPO_NAME="$(basename "$ROOT")"
WORKTREE_ROOT="${FORESTIQ_WORKTREE_ROOT:-$(dirname "$ROOT")/${REPO_NAME}-worktrees}"
BRANCH="ai/issue-${ISSUE}"
WORKTREE="$WORKTREE_ROOT/issue-${ISSUE}"

if [[ ! -d "$WORKTREE" ]]; then
  echo "Issue worktree not found: $WORKTREE" >&2
  echo "Run: just agent-issue $ISSUE cloud   (or local)" >&2
  exit 1
fi

cd "$WORKTREE"
CURRENT_BRANCH="$(git branch --show-current)"
if [[ "$CURRENT_BRANCH" != "$BRANCH" ]]; then
  echo "Expected branch $BRANCH, found $CURRENT_BRANCH" >&2
  exit 1
fi

if [[ -z "$(git status --porcelain)" ]]; then
  echo "No changes to publish for issue #$ISSUE."
  exit 0
fi

echo "Running local quality gate before publishing..."
git diff --check
just verify

git add -A
git diff --cached --check

if git diff --cached --quiet; then
  echo "No staged changes to publish."
  exit 0
fi

TITLE="$(gh issue view "$ISSUE" --json title --jq '.title')"
git commit -m "Implement issue #${ISSUE}: ${TITLE}"
git push -u origin "$BRANCH"

EXISTING_PR="$(gh pr list --head "$BRANCH" --state open --json number --jq '.[0].number // empty')"
if [[ -n "$EXISTING_PR" ]]; then
  echo "PR #$EXISTING_PR already exists; pushed the latest commit."
  gh pr view "$EXISTING_PR" --web || true
  exit 0
fi

BODY=$(cat <<EOF
Closes #$ISSUE

## Verification

- \`just verify\`
- \`git diff --check\`

Created by the local ForestIQ agent workflow. This PR is intentionally opened as a draft for human review before merge.
EOF
)

gh pr create \
  --draft \
  --base main \
  --head "$BRANCH" \
  --title "Issue #${ISSUE}: ${TITLE}" \
  --body "$BODY"
