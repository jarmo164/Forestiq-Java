# ForestIQ AI-first development workflow

This workflow turns an Apple Silicon Mac into the primary development/verification machine and uses cloud CI only as a secondary confirmation when available.

## Design

- **Codex cloud**: difficult cross-layer implementation, architecture, hard debugging, final review.
- **Qwen3-Coder 30B through Ollama**: local low-cost implementation, repository analysis, tests, repetitive refactors and documentation.
- **Git worktrees**: one isolated branch/directory per GitHub issue.
- **`just`**: one stable interface for bootstrap, tests, builds, agents and PR publishing.
- **Colima + Docker CLI**: lightweight local containers without requiring Docker Desktop.
- **OpenClaw**: optional orchestration layer; existing OpenClaw configuration is preserved.

## One-time Mac setup

After this setup change has been merged and the repository is on `main`:

```bash
git switch main
git pull
bash scripts/bootstrap-mac-ai.sh
```

The bootstrap installs/updates Homebrew tooling, Java 21, Python 3.13, Node 22, pnpm, GitHub CLI, Codex CLI, Ollama, Docker CLI/Compose, Colima, `just` and common command-line utilities. It starts Ollama and downloads `qwen3-coder:30b`.

If GitHub CLI or Codex are not authenticated, an interactive terminal will start their normal login flow. Existing OpenClaw configuration is not overwritten; when OpenClaw is already installed the bootstrap only verifies Ollama model discovery.

Run this after setup:

```bash
just doctor
```

## Implement one issue with cloud Codex

```bash
just agent-issue 123 cloud
```

The command:

1. fetches `origin/main`;
2. creates/reuses `../ForestIQ-worktrees/issue-123`;
3. creates/reuses branch `ai/issue-123`;
4. prepares worktree dependencies before the sandboxed agent starts;
5. loads the GitHub issue;
6. asks Codex to follow `AGENTS.md` and `PLANS.md`;
7. lets Codex edit and test only inside that worktree;
8. leaves all changes local for inspection.

## Implement one issue with the local model

```bash
just agent-issue 123 local
```

This uses `qwen3-coder:30b` through Ollama and Codex OSS mode. Use it for repository analysis, straightforward bugs, tests, CRUD, repetitive changes, documentation and first-pass refactors. Escalate difficult architecture, subtle cross-layer defects or security-sensitive work to cloud Codex.

## Review before publishing

Open the issue worktree and inspect it yourself or run an independent read-only Codex review:

```bash
cd ../ForestIQ-worktrees/issue-123
just agent-review
```

Useful manual checks:

```bash
git status
git diff
git diff --check
just verify-fast
```

## Publish a verified draft PR

From the main ForestIQ checkout:

```bash
just agent-publish 123
```

Publishing is intentionally a separate explicit command. It:

1. requires the expected issue worktree and branch;
2. runs `git diff --check`;
3. runs the full local `just verify` gate;
4. stages the complete verified diff;
5. commits it;
6. pushes `ai/issue-123`;
7. creates a **draft** pull request that closes the issue when merged.

It does **not** merge the PR.

## Quality gates

```bash
just backend-test     # Gradle/JUnit backend
just ingest-test      # Python/pytest ingest
just frontend-check   # ESLint + TypeScript + Vitest + Next production build
just contract-test    # ingestion deployment contracts
just verify-fast      # faster local development gate
just verify           # mandatory full pre-PR gate
```

## Local development stack

Pilot stack:

```bash
just dev
```

Full integration/observability stack:

```bash
just dev-full
```

Inspect services:

```bash
just status
just logs api
```

## Parallel agents

Every issue gets its own worktree, so several cloud Codex tasks may run in parallel without sharing a working directory. Keep local-model concurrency conservative: one 30B Ollama coding session at a time is the default recommendation so Docker, Java builds, Next.js and the OS retain memory headroom.

Example terminals:

```bash
just agent-issue 201 cloud
just agent-issue 202 cloud
just agent-issue 203 local
```

The local model is the cost-saving worker; cloud Codex is the escalation path.

## Security model

- Never use `--dangerously-bypass-approvals-and-sandbox` for this workflow.
- Issue bodies, web responses, registry data, uploaded files and logs are untrusted input.
- Do not put secrets in issue text, prompts, logs or committed files.
- Keep `.env` and credential stores out of the repository.
- `agent-publish` always runs local verification and opens a draft PR instead of merging automatically.
- Human review remains the final authority before merge/deploy.

## Updating the workstation

Re-run:

```bash
bash scripts/bootstrap-mac-ai.sh
```

The setup is intentionally idempotent where practical: Homebrew upgrades/install checks are reused, shell configuration markers are only added once, Docker Compose configuration is merged, existing OpenClaw configuration is left intact, and the local Ollama model is pulled only when missing.
