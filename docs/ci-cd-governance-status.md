# CI/CD governance status

This document separates **code merged**, **configured** and **live verified** status for the CI/CD backlog. The machine-readable source of truth is `config/ci-cd-governance-status.json`.

## Status meanings

- **code_merged** — implementation, guardrails and evidence tooling exist in `main`.
- **configured** — the external GitHub/runner/Render setting has been applied.
- **live_verified** — live evidence proves the configured behavior actually works.

An issue must not move to `live_verified` without a concrete evidence reference.

## Current operational blockers

| Issue | Current level | Why it remains open |
| --- | --- | --- |
| #388 | code_merged | Same-SHA canonical green `main` baseline has not been captured. |
| #430 | code_merged | CI split exists, but Heavy CI still needs live Mac Studio execution evidence. |
| #431 | code_merged | Runner registration, reboot recovery and security acceptance remain live checks. |
| #434 | code_merged | Server-side branch protection and auto-merge are not fully verified. |
| #440 | code_merged | Need repository-scoped Online runner evidence. |
| #441 | code_merged | Need before/after reboot evidence. |
| #442 | code_merged | Need live secret/user/permission acceptance evidence. |
| #456 | code_merged | Need server-side main protection evidence. |
| #457 | code_merged | Repository currently reports auto-merge disabled. |

## Code-complete child work

#436, #437, #438, #439, #443, #444 and #455 are closed because their acceptance is repository implementation/contract work and the corresponding code is present in `main`.

This status file intentionally does **not** treat a merged installer, audit script or policy file as proof that an external system is configured.
