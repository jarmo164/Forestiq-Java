set shell := ["bash", "-cu"]

# Show available commands.
default:
    @just --list

# Verify required workstation tools.
doctor:
    @for cmd in git gh java python3.13 node corepack docker just codex ollama; do \
      command -v "$cmd" >/dev/null || { echo "Missing: $cmd"; exit 1; }; \
    done
    @echo "Workstation tools: OK"
    @java -version 2>&1 | head -n 1
    @python3.13 --version
    @node --version
    @pnpm --version || true
    @docker --version
    @codex --version
    @ollama --version

# Install project dependencies after cloning.
bootstrap:
    corepack enable
    cd frontend && pnpm install --frozen-lockfile
    if [ ! -d ingest/.venv ]; then python3.13 -m venv ingest/.venv; fi
    ingest/.venv/bin/python -m pip install --upgrade pip
    ingest/.venv/bin/python -m pip install -e './ingest[test,geo]'
    cd frontend && pnpm exec playwright install chromium
    @echo "ForestIQ dependencies installed."

backend-test:
    cd backend && ./gradlew test --no-daemon

ingest-test:
    test -x ingest/.venv/bin/python || { echo "Run: just bootstrap"; exit 2; }
    cd ingest && .venv/bin/python -m pytest

frontend-lint:
    cd frontend && pnpm lint

frontend-test:
    cd frontend && pnpm test

frontend-build:
    cd frontend && pnpm build

frontend-check:
    cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build

contract-test:
    python3 -m unittest scripts/test_ingestion_deployment_contract.py scripts/test_verify_production_ingestion.py

# Main local quality gate used before push/PR.
verify: backend-test ingest-test frontend-check contract-test
    @echo "ForestIQ verification complete."

# Fast gate for small changes.
verify-fast: backend-test ingest-test
    cd frontend && pnpm lint && pnpm typecheck && pnpm test

# Start the pilot stack.
dev:
    docker compose up --build

# Start the full observability/integration stack.
dev-full:
    docker compose -f compose.yaml -f compose.full.yaml up --build

up:
    docker compose up -d

down:
    docker compose down

status:
    docker compose ps

logs service="":
    #!/usr/bin/env bash
    if [[ -n "{{service}}" ]]; then
      docker compose logs -f --tail=200 "{{service}}"
    else
      docker compose logs -f --tail=200
    fi

# Run one GitHub issue in an isolated worktree. provider = cloud|local
agent-issue issue provider="cloud":
    bash scripts/agent-issue.sh "{{issue}}" "{{provider}}"

# Verify, commit, push and open/update a draft PR for an issue worktree.
agent-publish issue:
    bash scripts/agent-publish.sh "{{issue}}"

# Review the current branch with Codex without changing files.
agent-review:
    codex exec --sandbox read-only "Review the current ForestIQ branch against main. Read AGENTS.md. Identify correctness, security, architecture-boundary, migration, API-contract and test gaps. Do not modify files."
