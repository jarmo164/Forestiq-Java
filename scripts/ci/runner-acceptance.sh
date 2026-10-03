#!/usr/bin/env bash
set -Eeuo pipefail

report="${RUNNER_ACCEPTANCE_REPORT:-artifacts/runner/acceptance.md}"
json_report="${RUNNER_ACCEPTANCE_JSON:-artifacts/runner/acceptance.json}"
install_dir="${RUNNER_INSTALL_DIR:-$HOME/actions-runner}"
expected_name="${EXPECTED_RUNNER_NAME:-koodikratt}"
mkdir -p "$(dirname "$report")" "$(dirname "$json_report")"

failures=0
rows=()

record() {
  local name="$1"
  local status="$2"
  local detail="$3"
  rows+=("| ${name} | ${status} | ${detail//$'\n'/ } |")
  if [[ "$status" != "PASS" ]]; then
    failures=$((failures + 1))
  fi
}

if [[ "$(uname -s)" == "Darwin" ]]; then
  record "macOS" "PASS" "$(sw_vers -productVersion 2>/dev/null || uname -r)"
else
  record "macOS" "FAIL" "$(uname -s)"
fi

if [[ "$(uname -m)" == "arm64" ]]; then
  record "Apple Silicon" "PASS" "arm64"
else
  record "Apple Silicon" "FAIL" "$(uname -m)"
fi

if [[ "${RUNNER_NAME:-}" == "$expected_name" ]]; then
  record "Runner name" "PASS" "${RUNNER_NAME}"
else
  record "Runner name" "FAIL" "expected=$expected_name actual=${RUNNER_NAME:-unset}"
fi

if [[ "${RUNNER_OS:-}" == "macOS" && "${RUNNER_ARCH:-}" == "ARM64" ]]; then
  record "GitHub runner context" "PASS" "RUNNER_OS=${RUNNER_OS}; RUNNER_ARCH=${RUNNER_ARCH}"
else
  record "GitHub runner context" "FAIL" "RUNNER_OS=${RUNNER_OS:-unset}; RUNNER_ARCH=${RUNNER_ARCH:-unset}"
fi

missing=()
for tool in git docker node pnpm java python3; do
  command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
done
if [[ "${#missing[@]}" -eq 0 ]]; then
  record "Required tools" "PASS" "git,docker,node,pnpm,java,python3"
else
  record "Required tools" "FAIL" "missing: ${missing[*]}"
fi

if docker info >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  record "Docker" "PASS" "$(docker compose version 2>/dev/null | head -n 1)"
else
  record "Docker" "FAIL" "daemon or Compose unavailable"
fi

if [[ -f "$install_dir/.runner" && -x "$install_dir/svc.sh" ]]; then
  if service_status="$(cd "$install_dir" && ./svc.sh status 2>&1)"; then
    record "launchd service" "PASS" "$(printf '%s' "$service_status" | tail -n 1)"
  else
    record "launchd service" "FAIL" "$(printf '%s' "$service_status" | tail -n 1)"
  fi
else
  record "launchd service" "FAIL" "runner not configured at $install_dir"
fi

if id -Gn | tr ' ' '\n' | grep -qx admin; then
  record "Dedicated non-admin account" "FAIL" "$(id -un) belongs to admin group"
else
  record "Dedicated non-admin account" "PASS" "$(id -un)"
fi

if secret_audit="$(python3 scripts/ci/runner_environment_security.py 2>&1)"; then
  record "Production secret environment" "PASS" "$secret_audit"
else
  record "Production secret environment" "FAIL" "$secret_audit"
fi

if bash scripts/ci/cleanup-self-hosted.sh >/tmp/forestiq-runner-cleanup.log 2>&1; then
  record "Scoped cleanup" "PASS" "$(tail -n 1 /tmp/forestiq-runner-cleanup.log)"
else
  record "Scoped cleanup" "FAIL" "$(tail -n 1 /tmp/forestiq-runner-cleanup.log)"
fi
rm -f /tmp/forestiq-runner-cleanup.log

boot_epoch="$(sysctl -n kern.boottime 2>/dev/null | sed -E 's/.*sec = ([0-9]+).*/\1/' || true)"
[[ "$boot_epoch" =~ ^[0-9]+$ ]] || boot_epoch=0
captured_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
acceptance_status="PASS"
[[ "$failures" -eq 0 ]] || acceptance_status="FAIL"

{
  echo "# ForestIQ Mac Studio runner acceptance"
  echo
  printf -- '- Runner: `%s`\n' "${RUNNER_NAME:-unset}"
  printf -- '- User: `%s`\n' "$(id -un)"
  printf -- '- Repository: `%s`\n' "${GITHUB_REPOSITORY:-unset}"
  printf -- '- Run: `%s/%s`\n' "${GITHUB_RUN_ID:-local}" "${GITHUB_RUN_ATTEMPT:-1}"
  printf -- '- Boot epoch: `%s`\n' "$boot_epoch"
  echo
  echo "| Check | Result | Detail |"
  echo "| --- | --- | --- |"
  printf '%s\n' "${rows[@]}"
  echo
  echo "**Acceptance: $acceptance_status**"
} > "$report"

python3 - "$json_report" "$acceptance_status" "$boot_epoch" "$captured_at" <<'PY'
import json
import os
import sys
from pathlib import Path

path, status, boot_epoch, captured_at = sys.argv[1:]
payload = {
    "schemaVersion": 1,
    "acceptance": status,
    "runnerName": os.environ.get("RUNNER_NAME", "unset"),
    "runnerOs": os.environ.get("RUNNER_OS", "unset"),
    "runnerArch": os.environ.get("RUNNER_ARCH", "unset"),
    "repository": os.environ.get("GITHUB_REPOSITORY", "unset"),
    "runId": os.environ.get("GITHUB_RUN_ID", "local"),
    "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT", "1"),
    "user": os.environ.get("USER") or os.environ.get("LOGNAME") or "unknown",
    "bootEpoch": int(boot_epoch),
    "capturedAt": captured_at,
}
Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

cat "$report"
[[ "$failures" -eq 0 ]]
