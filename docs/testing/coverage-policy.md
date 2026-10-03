# Backend coverage and critical regression policy

ForestIQ treats authorization, owner workflows and external-data recovery as higher-risk code paths than ordinary presentation or mapping helpers. Coverage gates therefore have both a repository-wide floor and a stronger package-level floor for critical modules.

## Current enforced gates

`backend/build.gradle.kts` is the source of truth and `./gradlew check` enforces the values in CI.

| Scope | Line coverage | Branch coverage |
| --- | ---: | ---: |
| Whole backend | 30% | 18% |
| Critical packages | 35% | 20% |

Critical packages are `security`, `owner`, `inheritance`, `deal`, `registry`, `portfolio`, `ownership` and `integration`.

These values are floors, not targets. A change must not reduce either gate simply to make CI pass.

## Staged target

Coverage is raised in measured steps so the gate remains meaningful instead of encouraging low-value tests written only for a percentage.

1. **Stage 1 — baseline protection:** current 30% line / 18% branch globally and 35% / 20% in critical packages.
2. **Stage 2 — workflow protection:** raise to at least 40% / 25% globally and 45% / 30% in critical packages after CI measurement shows adequate headroom.
3. **Stage 3 — target:** reach at least 60% line / 40% branch globally, with critical workflow packages kept above the global floor and preferably at least 65% / 45%.

Each increase should leave several percentage points of normal development headroom. Thresholds should move upward when the rolling CI result supports it; they should not be lowered because of a new feature.

## Pinned critical regressions

`CriticalRegressionContractTest` makes the normal test task fail if a pinned high-risk regression test is removed or renamed without an explicit replacement. The pinned set covers:

- caller authorization isolation and co-owned parcel access;
- Keycloak assignment/deletion safety and directory pagination;
- Weasel failure checkpoint persistence;
- MetsIS failure checkpoint persistence;
- resumable Metsaregister bulk recovery;
- WFS paging safety limits.

When a pinned test is intentionally replaced, update the contract in the same commit and preserve equivalent or stronger negative/failure-path coverage.

## Required testing style for critical modules

New authorization rules require at least one negative test in addition to happy-path coverage. External integrations require tests for provider failure, retry/resume or page-boundary behavior as applicable. Snapshot-style integrations must prove that partial or failed reads cannot replace the last-known-good live projection.

End-to-end Playwright coverage remains required for cross-role workflows where the browser/API boundary is material. Unit and integration coverage is not a substitute for role-boundary E2E tests, and E2E tests are not a substitute for deterministic failure-path tests.
