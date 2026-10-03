#!/usr/bin/env bash
set -Eeuo pipefail

repo="${RUNNER_REPO:-jarmo164/ForestIQ}"
install_dir="${RUNNER_INSTALL_DIR:-$HOME/actions-runner}"
runner_name="${RUNNER_NAME:-koodikratt}"
runner_token="${RUNNER_TOKEN:-}"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "This installer is intended for Apple Silicon macOS." >&2
  exit 1
fi

if [[ -z "$runner_token" ]]; then
  cat >&2 <<'EOF'
RUNNER_TOKEN is required.
Create a short-lived repository runner registration token in:
GitHub -> ForestIQ -> Settings -> Actions -> Runners -> New self-hosted runner
Then run:
  RUNNER_TOKEN=... bash scripts/install-self-hosted-runner.sh
EOF
  exit 1
fi

for tool in curl tar; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Missing required tool: $tool" >&2
    exit 1
  }
done

mkdir -p "$install_dir"
cd "$install_dir"

if [[ ! -x ./config.sh ]]; then
  echo "Resolving latest GitHub Actions runner release..."
  version="$(
    curl -fsSL https://api.github.com/repos/actions/runner/releases/latest       | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"].lstrip("v"))'
  )"
  archive="actions-runner-osx-arm64-${version}.tar.gz"
  url="https://github.com/actions/runner/releases/download/v${version}/${archive}"

  echo "Downloading GitHub Actions runner ${version}..."
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fL "$url" -o "$tmp/$archive"
  tar xzf "$tmp/$archive" -C "$install_dir"
fi

if [[ -f .runner ]]; then
  echo "Runner is already configured at $install_dir."
  echo "Remove it with ./config.sh remove --token <remove-token> before re-registering."
  exit 0
fi

./config.sh   --unattended   --replace   --url "https://github.com/${repo}"   --token "$runner_token"   --name "$runner_name"   --labels "forestiq,heavy-ci"   --work "_work"

unset runner_token RUNNER_TOKEN

echo
echo "Runner configured:"
echo "  repository: $repo"
echo "  name:       $runner_name"
echo "  directory:  $install_dir"
echo "  labels:     self-hosted, macOS, ARM64, forestiq, heavy-ci"
echo
echo "Next:"
echo "  bash scripts/install-self-hosted-runner-service.sh"
echo "Then run the GitHub workflow: Self-hosted runner diagnostics."
