# CI baseline recovery

Issue #388 tracks restoration of a proven green CI baseline.

## Current failure class

The GitHub-hosted `CI` and `Security` runs observed on 2026-09-21 fail before any workflow step starts. The affected jobs report no executed steps, so this is treated as a runner-provisioning/Actions-availability failure rather than evidence that a ForestIQ test failed.

Do not classify `main` as green until the canonical hosted workflows actually execute and pass.

## Self-hosted fallback

When GitHub-hosted capacity is unavailable, maintainers can run **Actions -> CI / Self-hosted fallback -> Run workflow** with either `fast` or `full`.

The fallback is diagnostic/recovery evidence. It does **not** replace canonical required checks.

## Machine-checkable main baseline

Export recent runs and prove one concrete `main` SHA:

~~~bash
gh run list --branch main --limit 100 \
  --json workflowName,conclusion,headSha,event,createdAt,url \
  > /tmp/forestiq-main-runs.json

python scripts/ci/main_ci_baseline_report.py /tmp/forestiq-main-runs.json \
  --main-sha "$(git rev-parse origin/main)" \
  --output /tmp/forestiq-main-ci-baseline.md \
  --require-green
~~~

The checker requires successful `CI`, `Heavy CI` and `Ingestion Contract` runs for the **same SHA**. An older green run cannot prove the current baseline.

## Baseline closure checklist

Before #388 can close:

- [ ] a current `main` commit completes the canonical `CI` workflow;
- [ ] the same commit completes heavy/E2E verification successfully;
- [ ] the same commit completes ingestion contract verification successfully;
- [ ] required aggregate gates are enforced by repository protection;
- [ ] failure diagnostics are retained for failed implementation jobs;
- [ ] `docs/ci-development-workflow.md` matches the deployed workflow model.
