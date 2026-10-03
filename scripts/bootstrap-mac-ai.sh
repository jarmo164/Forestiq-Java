#!/usr/bin/env bash
set -euo pipefail

log() { printf '\n==> %s\n' "$*"; }
warn() { printf '\nWARNING: %s\n' "$*" >&2; }

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "This bootstrap is intended for Apple Silicon macOS." >&2
  exit 1
fi

log "Checking Apple developer tools"
if ! xcode-select -p >/dev/null 2>&1; then
  xcode-select --install || true
  echo "Finish the Apple Command Line Tools installer and run this script again." >&2
  exit 1
fi

log "Installing Homebrew when needed"
if ! command -v brew >/dev/null 2>&1; then
  NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

eval "$(/opt/homebrew/bin/brew shellenv)"
mkdir -p "$HOME"
touch "$HOME/.zprofile" "$HOME/.zshrc"
if ! grep -Fq 'eval "$(/opt/homebrew/bin/brew shellenv)"' "$HOME/.zprofile"; then
  printf '\neval "$(/opt/homebrew/bin/brew shellenv)"\n' >> "$HOME/.zprofile"
fi

log "Installing development and AI tooling"
brew update
brew install git gh jq just ripgrep fd fzf python@3.13 openjdk@21 node@22 colima docker docker-compose ollama
brew install --cask codex

BREW_PREFIX="$(brew --prefix)"
AI_MARKER='# >>> ForestIQ AI workstation >>>'
if ! grep -Fq "$AI_MARKER" "$HOME/.zshrc"; then
  cat >> "$HOME/.zshrc" <<EOF

$AI_MARKER
export PATH="$BREW_PREFIX/opt/openjdk@21/bin:$BREW_PREFIX/opt/node@22/bin:\$PATH"
export JAVA_HOME="$BREW_PREFIX/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
# OpenClaw/Ollama use a local marker key; Ollama itself does not require a real API key.
export OLLAMA_API_KEY="ollama-local"
# <<< ForestIQ AI workstation <<<
EOF
fi

export PATH="$BREW_PREFIX/opt/openjdk@21/bin:$BREW_PREFIX/opt/node@22/bin:$PATH"
export JAVA_HOME="$BREW_PREFIX/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export OLLAMA_API_KEY="ollama-local"

log "Enabling pnpm through Corepack"
corepack enable
corepack prepare pnpm@9.15.4 --activate

log "Configuring Docker Compose plugin"
mkdir -p "$HOME/.docker"
DOCKER_PLUGIN_DIR="$BREW_PREFIX/lib/docker/cli-plugins"
DOCKER_CONFIG="$HOME/.docker/config.json"
if [[ -f "$DOCKER_CONFIG" ]]; then
  TMP_CONFIG="$(mktemp)"
  if jq --arg d "$DOCKER_PLUGIN_DIR" '.cliPluginsExtraDirs = (((.cliPluginsExtraDirs // []) + [$d]) | unique)' "$DOCKER_CONFIG" > "$TMP_CONFIG"; then
    mv "$TMP_CONFIG" "$DOCKER_CONFIG"
  else
    rm -f "$TMP_CONFIG"
    warn "$DOCKER_CONFIG is not valid JSON; Docker Compose plugin path was not changed."
  fi
else
  jq -n --arg d "$DOCKER_PLUGIN_DIR" '{cliPluginsExtraDirs: [$d]}' > "$DOCKER_CONFIG"
fi

log "Starting the lightweight Colima container runtime"
if ! docker info >/dev/null 2>&1; then
  colima start --cpu 6 --memory 12 --disk 80
fi

docker info >/dev/null

docker compose version

log "Starting Ollama and installing the local coding model"
brew services start ollama >/dev/null || true
for _ in {1..30}; do
  if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if ! curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
  echo "Ollama did not become ready on 127.0.0.1:11434." >&2
  exit 1
fi

if ! ollama list | awk 'NR>1 {print $1}' | grep -Fxq 'qwen3-coder:30b'; then
  ollama pull qwen3-coder:30b
fi

log "Checking GitHub authentication"
if ! gh auth status >/dev/null 2>&1; then
  if [[ -t 0 && -t 1 ]]; then
    gh auth login
  else
    warn "GitHub CLI is not authenticated. Run: gh auth login"
  fi
fi

log "Checking Codex authentication"
if ! codex login status >/dev/null 2>&1; then
  if [[ -t 0 && -t 1 ]]; then
    codex login
  else
    warn "Codex is not authenticated. Run: codex login"
  fi
fi

if command -v openclaw >/dev/null 2>&1; then
  log "Verifying OpenClaw can see local Ollama models"
  openclaw models list --provider ollama || warn "OpenClaw is installed but Ollama model discovery needs attention."
fi

# If run from the ForestIQ repository, prepare project-local dependencies too.
if git rev-parse --show-toplevel >/dev/null 2>&1 && [[ -f "$(git rev-parse --show-toplevel)/justfile" ]]; then
  ROOT="$(git rev-parse --show-toplevel)"
  log "Bootstrapping ForestIQ project dependencies"
  cd "$ROOT"
  if [[ ! -f .env && -f .env.example ]]; then
    cp .env.example .env
    warn "Created .env from .env.example. Fill any project-specific secrets before starting the full stack."
  fi
  just bootstrap
  just doctor
fi

log "AI developer workstation is ready"
cat <<'EOF'

Typical ForestIQ workflow after this setup branch is merged into main:

  just agent-issue 123 cloud    # strongest model for difficult work
  just agent-issue 123 local    # Qwen3-Coder 30B, no model API cost
  just agent-review             # independent read-only Codex review
  just agent-publish 123        # verify, commit, push and open a draft PR

Useful checks:

  just doctor
  just verify-fast
  just verify
  ollama list
  colima status
EOF
