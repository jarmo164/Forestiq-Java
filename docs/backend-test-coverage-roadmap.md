# Backend test coverage roadmap

ForestIQ's backend coverage gate is intentionally raised in staged increments so CI blocks real regressions without forcing low-value tests around every uncovered line at once.

## Current enforced gate

The current hardening step enforces:

| Scope | Line coverage | Branch coverage |
| --- | ---: | ---: |
| Whole backend | 30% | 18% |
| Critical workflow packages | 35% | 20% |

Critical packages are `security`, `owner`, `inheritance`, `deal`, `registry`, `portfolio`, `ownership` and `integration`. These package rules prevent a heavily tested utility or presentation area from hiding weak coverage in authorization and synchronization code.

The previous global floor was 25% line / 14% branch. The new stage is paired with negative authorization tests, pagination boundary tests, OIDC/session tests, assignment/recipient validation, transaction-failure persistence and WFS recovery coverage.

## Staged target

| Stage | Global target | Critical-package target | Required focus before raising |
| --- | --- | --- | --- |
| Current | 30% line / 18% branch | 35% / 20% | Security and integration regression tests remain green. |
| Next | 40% line / 25% branch | 50% / 30% | Bulk registry recovery, contract storage reconciliation and admin assignment failure paths. |
| Pre-release | 50% line / 32% branch | 60% / 40% | Deal/inheritance state machines and provider retry/cancellation paths. |
| Target | **60% line / 40% branch** | 70% / 50% for sensitive modules | Sustained without excluding business logic from coverage. |

Threshold increases must be accompanied by behavior tests; coverage is not raised by excluding untested production packages.

## Critical regression paths

Higher-value tests take priority over percentage chasing:

- owner authorization: role, organization, assignment, co-owner and multi-role boundaries;
- map/cadastre authorization: assignment-scoped parcel and owner-derived data;
- owner mutation: status changes, assignment, stale write conflicts and activity logging;
- OIDC/BFF session handling: state/PKCE rejection, refresh and logout token handling;
- reminders/messages: personal resource ownership and authoritative recipient validation;
- deal workflow: qualification, evaluation, negotiation, offer lifecycle, accepted-offer contract invariants and forbidden transitions;
- deal/evaluation concurrency: stale transitions and evaluation revision conflicts fail explicitly;
- inheritance: resource authorization and assignee-role validation;
- registry: WFS page boundaries, persisted resume cursor, per-run cap, atomic snapshot apply and audit retention;
- MetsIS/Weasel integrations: provider failure metadata survives thrown exceptions while success cursors remain unchanged;
- contract storage: object-store outage/corruption falls back to immutable DB content and reconciliation repairs the object copy.

## CI rule

`./gradlew check` owns global and package-level JaCoCo verification. Playwright remains responsible for cross-role end-to-end authorization checks. CI must fail when the negative security/integration recovery tests or their covered behavior regress.
