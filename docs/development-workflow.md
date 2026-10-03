# Development and CI workflow

## Active rewrite branch

The active integration branch is `rewrite/next-spring-postgis`.

GitHub Actions runs the full `CI` workflow automatically for:

- every push to `rewrite/next-spring-postgis`;
- every push to `main`;
- every pull request;
- manual `workflow_dispatch` runs.

A rewrite commit is not considered validated merely because it exists on GitHub. It is considered green only when the CI run for that exact commit has completed successfully.

## Required quality gates

The CI workflow contains these integration gates:

1. **Backend** — Gradle `check` and JaCoCo quality gates.
2. **Generated jOOQ schema** — `generateJooq` followed by a clean diff check.
3. **Frontend** — lint, typecheck, unit tests with coverage, build and dependency audit.
4. **Compose and Images** — Compose validation, service image builds and Trivy scanning.
5. **Playwright E2E** — deterministic seeded stack and critical user flows.

The Playwright job depends on backend, frontend and Compose/image jobs, so an E2E success represents the full upstream build chain as well.

## Pull request / main policy

Before merging rewrite work to `main`:

- the head commit must have an actual successful CI run;
- generated schema/contracts must be committed and pass their diff checks;
- do not treat a local build, a stale earlier workflow run or the presence of a branch commit as equivalent to CI success.

## Repository protection setting

The repository should protect `main` and require the GitHub Actions checks produced by the `CI` workflow before merge. At minimum require the backend, frontend, Compose/image and Playwright E2E jobs. When generated OpenAPI verification is enabled, it is part of the frontend required check.

This repository setting is intentionally separate from the workflow YAML: YAML can trigger and define checks, but GitHub branch protection/rulesets are what make them enforceable before merge.
