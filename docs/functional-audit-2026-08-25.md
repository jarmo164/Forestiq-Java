# ForestIQ functional audit — 2026-08-25

## Scope

This audit reviews the current `main` branch after the 24 August integration and ownership-sync work. Audit baseline: `eaa19e0e6bc8a64aad48c76a73d0ddf7dbbe20b5`.

The review covered:

- authentication and authorization;
- owner CRM, assignment, activities and followings;
- cadastral data and map access;
- owner import;
- deals, evaluation, negotiation and sales workspace;
- contracts and PDF storage;
- inheritance workflows;
- reminders and internal messaging;
- Maa-amet / Metsaregister WFS integration;
- Metsis/Forestek portfolio snapshot integration;
- Weasel ownership-transition integration;
- integration operations/admin runner;
- Keycloak user administration;
- database migrations, PostGIS and object storage;
- Docker Compose / production configuration;
- CI, OpenAPI, Playwright and coverage gates.

This is primarily a static code/configuration audit. It identifies concrete defects and architectural risks visible in the repository; it is not a substitute for production load testing, penetration testing or provider-side contract testing.

## Executive summary

The rewrite is substantially healthier than the earlier operational-readiness audit. Core owner CRUD, deal/evaluation transitions, owner-import preview/commit, contract persistence, integration-run tracking and CI/E2E infrastructure are now present and reasonably structured.

The most important remaining risks are concentrated in four areas:

1. **Authorization/session security** — browser token handling is still too exposed and one inheritance endpoint bypasses assignment-level authorization.
2. **Ownership model consistency** — `owner_parcel_relation` is now the real many-to-many ownership model, but several map/cadastre/registry/Weasel paths still make business and authorization decisions from the legacy `land_parcel.owner_id` column.
3. **Integration correctness/operability** — Weasel can advance a checkpoint after a truncated page window; Metsis/Weasel keep DB transactions open over network I/O; Compose does not expose all scheduler/WFS settings consistently.
4. **Production hardening** — the default Compose stack still runs the API under the `core` profile and Keycloak with `start-dev`; production guards therefore do not protect that path.

Recommended release posture: **do not treat the current branch as production-ready until P0 issues #32–#35 are resolved and P1 deployment/integration issues #37–#41 are addressed or explicitly accepted.**

## Functional-area status

| Area | Status | Findings |
|---|---|---|
| Authentication / OIDC | 🔴 Critical | PKCE exists, but OIDC `state` is missing and refresh tokens are browser-readable (#32). |
| Tenant isolation | 🟢 Good baseline | `OrganizationContext` and organization-scoped repositories are broadly used. Remaining cross-resource issues are assignment/ownership-model problems rather than basic tenant leakage. |
| Owner CRM | 🟡 Mostly sound | CRUD, optimistic locking, assignment restriction and import are improved. Assignment target identity validation is missing (#40). |
| Owner import | 🟢 Good | Inspect → preview → SHA-verified commit, row/file limits and documented manual recovery are present. No separate defect opened. |
| Cadastre / map | 🔴 Critical | Access and owner display still rely on legacy `land_parcel.owner_id` instead of active owner relations (#33). |
| Deal / evaluation | 🟡 Mostly sound | State transitions and evaluator claim/locking are improved. Assignee identity validation and holiday-aware SLA policy remain (#40, #48). |
| Sales workspace | 🟡 Mostly sound | Assignment-scoped queue/outcomes are present. Business-day logic is duplicated and ignores Estonian public holidays (#48). |
| Contracts | 🟡 Needs hardening | Deal-derived source validation exists, but can be bypassed entirely by omitting deal/offer IDs (#49). Download does not fall back to DB copy during later S3 outage (#50). |
| Inheritance | 🔴 Critical | Case list/detail assignment checks exist, but the owner-workspace endpoint bypasses CALLER assignment authorization (#34). |
| Reminders | 🟡 Authorization gap | Personal reads are creator-scoped, but delete is organization-scoped and linked-owner creation lacks assignment validation (#36). |
| Internal messaging | 🟡 Data-quality/scaling | Recipient is not validated against active organization users (#47); raw page/size parameters are not normalized (#44). |
| Keycloak admin | 🟡 Scaling/identity | User/role listing is not fully paged (#43); deleting users can orphan active assignments (#40). |
| Metsis / Forestek portfolio | 🟡 Reliability | Complete-snapshot idea is good, but network fetch runs inside DB transaction and failure checkpoint can roll back (#37). Downstream readers still use legacy parcel owner column (#33). |
| Weasel transitions | 🔴 Data-loss risk | Page cap can advance cursor over unconsumed events (#35); transaction/failure checkpoint issue (#37); owner matching uses legacy owner column (#33). |
| Maa-amet / Metsaregister WFS | 🟡 Scale/operations | Per-parcel CQL avoids huge single responses but recovery/initial sync is serial and lacks provider-level resumable paging/batching (#41); raw audit payload retention is unbounded (#42). |
| Integration admin runner | 🟢 Good baseline | Run history, active-run uniqueness, metrics and admin-only trigger are present. Runtime configuration can still disagree with Admin because of Compose drift (#38). |
| Docker / deployment | 🔴 Production gap | Default stack is `core` + Keycloak `start-dev`, bypassing production guards (#39); integration env drift (#38); ARM64 PostGIS warning/stale PG12 Dockerfile (#45). |
| CI / OpenAPI / E2E | 🟡 Good structure, weak depth | Main/rewrite triggers, live OpenAPI check, Compose build and Playwright gate exist. Backend coverage floor remains 25% line / 14% branch (#46). Existing #21 appears stale/superseded and should be triaged. |

## Findings grouped by root cause

### A. Security and resource authorization

#### #32 — P0: OIDC state and browser-readable refresh tokens

The login route creates a PKCE verifier/challenge but does not create or validate `state`. The callback writes the complete token set to `localStorage`, and the client refreshes using the browser-readable refresh token.

Primary files:

- `frontend/app/auth/login/route.ts`
- `frontend/app/auth/callback/route.ts`
- `frontend/lib/auth.tsx`

Required direction: server/BFF session or equivalent HttpOnly cookie design, state validation, CSRF model, consistent role extraction.

#### #33 — P0: canonical owner/parcel relation is not used consistently

The snapshot integration introduced `owner_parcel_relation` for multiple owners and relationship lifecycle. Several reads still join from `land_parcel.owner_id`.

Affected paths include:

- cadastre assignment authorization;
- map tile and map summary owner/contact data;
- registry data-change signal owner attribution;
- Weasel ownership-transition matching;
- compatibility owner behavior after transfer/co-ownership.

This is the highest-impact data-model defect because it can become both a privacy/authorization error and a business-data correctness error.

#### #34 — P0: inheritance owner workspace bypasses CALLER assignment restriction

`InheritanceService.workspace(ownerId)` verifies tenant ownership but not caller assignment. Newer case-centric methods do enforce assignment visibility, so the old owner-scoped endpoint has become an inconsistent bypass.

#### #36 — P1: reminder mutation authorization

Reminder reads are personal, but delete is scoped only by organization and ID. Owner-linked reminder creation also validates only tenant ownership, not the caller's owner assignment.

#### #40 — P1: assignment identity integrity and user deletion

Owner, evaluator and inheritance-case assignment endpoints accept subject strings without verifying that the subject is an active suitable user in the current organization. Keycloak user deletion does not reconcile active assignments.

#### #47 — P2: message recipient identity validation

Internal messages can be sent to arbitrary/stale subject strings. Active recipient choices should come from the same authoritative organization-user directory as assignments.

### B. Integration correctness and failure recovery

#### #35 — P0: Weasel checkpoint advances after truncated pagination

If every page up to `maxPagesPerRun` is full, `OwnershipTransitionService` still writes the checkpoint to `now`. Remaining older events can then be skipped permanently. The page cap must produce partial/resumable state, not success.

#### #37 — P1: network I/O inside DB transactions and rollback-prone failure checkpoints

Metsis and Weasel sync methods are transactional while performing external paging/retries. This can hold connections for a long period and failure-checkpoint updates in the same transaction can be rolled back when the exception is rethrown.

Use a fetch/validate phase outside the main transaction, then a short atomic apply phase. Failure audit/checkpoints must commit independently.

#### #38 — P1: Compose/application configuration drift

Spring supports more integration variables than Compose passes into the API container. Important examples are registry scheduler/WFS/RIK variables. Inheritance schedule defaults also disagree (30 min in Spring vs 24 h injected by Compose).

This can explain a UI showing a scheduler as off even when the operator believes `.env` enabled it.

#### #41 — P1: WFS scalability/pagination

The current targeted CQL approach is safe for one parcel but becomes thousands of serial requests for large portfolios/recovery. Bulk/page-window synchronization needs stable paging, resumable checkpoints, page-cap handling, batching where supported and bounded concurrency/rate limiting.

This is especially important for Metsaregister layers containing millions of records and upstream per-request row limits.

#### #42 — P2: unbounded raw registry audit storage

Full raw WFS responses and per-feature raw JSON are persisted repeatedly without a visible retention/size policy. Keep enough evidence for diagnosis/provenance, but bound payloads and retention.

### C. Business workflow invariants

#### #49 — P1: contract generation can bypass accepted deal source

`validateSource(null, null)` intentionally returns `null`, after which `/contracts/generate` accepts request-provided seller/parcels/price and stores a normal contract. If manual contracts are intended, they need an explicit separately audited workflow; otherwise dealId/offerEntryId should be mandatory.

#### #48 — P2: business-day calendar ignores Estonian holidays

`BusinessCalendar` excludes only weekends, and `SalesWorkspaceService` has a separate weekend-only implementation. SLA and follow-up due dates can land on national holidays. One shared Estonia-aware calendar should be used.

#### #50 — P2: contract download does not use DB fallback during S3 outage

Contract generation stores the PDF in the database before object storage, but once an `objectKey` exists, download always reads S3/MinIO. A later object-storage outage therefore blocks download despite the database copy.

### D. User administration and API scalability

#### #43 — P2: Keycloak Admin paging

`GET /users` is read as one page and role members use `max=1000` without paging. Large realms/organizations can silently lose users from ForestIQ Admin and assignment lists.

#### #44 — P2: pagination input normalization

Owner search has a shared page-size policy, but message list endpoints pass raw page/size values to SQL. Apply one bounded paging contract across controllers.

### E. Deployment, developer experience and quality gates

#### #39 — P1: production deployment path is not clearly separated from local development

Default Compose runs:

- API profile: `core`;
- Keycloak: `start-dev`.

`ProductionConfigurationGuard` activates only for `prod`/`production`, so reusing this stack outside local development bypasses fail-fast production validation.

#### #45 — P2: ARM64 PostGIS and stale PostgreSQL 12 Dockerfile

Apple Silicon gets a platform mismatch on the PostGIS image, while a separate stale `dockerfiles/postgres-dev/Dockerfile` still references PostgreSQL 12 without PostGIS. Keep one supported PostgreSQL 17/PostGIS development path and make architecture behavior explicit.

#### #46 — P1: critical-path regression coverage is still too low

Current backend JaCoCo gates are 25% line and 14% branch. New authorization/integration behavior needs negative-path and failure-recovery tests with stronger package-level expectations.

The CI workflow itself is structurally much better than the old audit: it now runs backend/frontend/Compose/Playwright gates and live OpenAPI generation. The remaining problem is depth of regression protection, not the absence of CI.

## New issues created by this audit

### P0 — block production readiness

- [#32 Harden OIDC login state and remove browser-readable refresh tokens](https://github.com/jarmo164/ForestIQ/issues/32)
- [#33 Make owner_parcel_relation canonical across map, cadastre and registry workflows](https://github.com/jarmo164/ForestIQ/issues/33)
- [#34 Close CALLER authorization bypass in inheritance owner workspace](https://github.com/jarmo164/ForestIQ/issues/34)
- [#35 Do not advance Weasel checkpoint when pagination is truncated](https://github.com/jarmo164/ForestIQ/issues/35)

### P1 — fix before dependable pilot/production operation

- [#36 Enforce reminder ownership on delete and owner-linked reminder creation](https://github.com/jarmo164/ForestIQ/issues/36)
- [#37 Separate integration network fetches from DB transactions and persist failure checkpoints](https://github.com/jarmo164/ForestIQ/issues/37)
- [#38 Align Compose environment with application integration configuration and scheduler defaults](https://github.com/jarmo164/ForestIQ/issues/38)
- [#39 Separate local Compose from production deployment and enable production guards](https://github.com/jarmo164/ForestIQ/issues/39)
- [#40 Validate workflow assignees and reconcile work before deleting Keycloak users](https://github.com/jarmo164/ForestIQ/issues/40)
- [#41 Make registry WFS synchronization paged, resumable and scalable](https://github.com/jarmo164/ForestIQ/issues/41)
- [#46 Raise regression coverage for authorization and integration critical paths](https://github.com/jarmo164/ForestIQ/issues/46)
- [#49 Prevent contract generation from bypassing the accepted deal/offer source](https://github.com/jarmo164/ForestIQ/issues/49)

### P2 — hardening/scalability/operability

- [#42 Add retention and payload limits for registry sync audit data](https://github.com/jarmo164/ForestIQ/issues/42)
- [#43 Paginate Keycloak Admin user and role-member queries](https://github.com/jarmo164/ForestIQ/issues/43)
- [#44 Apply shared pagination limits to message and list APIs](https://github.com/jarmo164/ForestIQ/issues/44)
- [#45 Make local PostGIS development stack ARM64-safe and remove stale PostgreSQL 12 image](https://github.com/jarmo164/ForestIQ/issues/45)
- [#47 Validate internal message recipients against active organization users](https://github.com/jarmo164/ForestIQ/issues/47)
- [#48 Include Estonian public holidays in business-day SLA calculations](https://github.com/jarmo164/ForestIQ/issues/48)
- [#50 Fall back to stored database contract PDF when object storage is unavailable](https://github.com/jarmo164/ForestIQ/issues/50)

## Existing issue requiring triage

- [#21 Run full CI automatically for the rewrite branch](https://github.com/jarmo164/ForestIQ/issues/21) is still open, but `.github/workflows/ci.yml` now triggers on both `main` and `rewrite/next-spring-postgis`, and includes backend/frontend/Compose/E2E integration gates. This issue appears largely superseded and should be closed or rewritten around branch-protection/enforcement if something is still missing.

## Areas reviewed without a new blocking defect

### Owner import

The owner import flow now has meaningful safety controls:

- 10 MB / 10,000-row / 100-column caps;
- CSV/XLSX parser constraints;
- formula rejection in XLSX;
- explicit column allow-list;
- dry-run preview and row-level classification;
- SHA-256 confirmation before commit;
- database uniqueness as race-condition backstop;
- batch audit and documented recovery strategy.

A one-click revert endpoint is intentionally absent because deleting imported owners after downstream business activity would be dangerous. The documented manual recovery policy is reasonable.

### Deal evaluation state transitions

The current deal flow has active-stage checks, evaluator claim/assignment logic, transactional evaluation revision creation and owner-status coordination. The major remaining cross-cutting weakness is assignee identity integrity (#40) and calendar policy (#48), rather than a missing state machine.

### Integration runner

`integration_sync_run`, active-run uniqueness, run state, metrics, recent history and admin-only manual start provide a good operations foundation. Problems are in provider implementation and effective runtime config (#35, #37, #38, #41), not the basic runner abstraction.

### CI/OpenAPI/E2E structure

The current workflow runs:

- backend Gradle check + generated jOOQ drift;
- frontend lint/typecheck/unit tests/build/audit;
- Compose validation/build and Trivy;
- Playwright E2E against a real Compose stack;
- live OpenAPI generation/typecheck;
- a final integration gate.

This closes much of the older structural CI gap. Coverage thresholds and specific regression scenarios remain insufficient (#46).

## Recommended implementation order

### Phase 1 — stop security/data-loss risks

1. #34 inheritance authorization bypass — small/high-value patch.
2. #35 Weasel truncated-pagination checkpoint — small/critical data-loss patch.
3. #32 OIDC state + session/token model — security boundary.
4. #33 canonical owner/parcel relation — larger refactor; do before trusting snapshot/co-ownership in production.

### Phase 2 — make integrations operationally trustworthy

5. #37 transaction/failure checkpoint refactor.
6. #38 configuration contract and scheduler defaults.
7. #41 WFS paging/batching/resume.
8. #42 audit retention after provider behavior is stabilized.

### Phase 3 — close workflow authorization/integrity gaps

9. #36 reminder authorization.
10. #40 authoritative subject validation + safe user deletion.
11. #47 message recipient validation (reuse #40 subject-directory abstraction).
12. #49 contract source invariant.

### Phase 4 — production/deployment hardening

13. #39 production profile + production Keycloak path.
14. #45 ARM64/dev database cleanup.
15. #43 Keycloak paging.
16. #44 shared API pagination.
17. #48 holiday calendar.
18. #50 contract storage fallback.

### Continuous quality work

19. #46 should be implemented alongside the issues above: every fixed defect should add the regression test that would have caught it.

## Dependency notes

- **#33 should precede or be coordinated with #41** because WFS/registry signal ownership must target the canonical relationship model.
- **#40 should provide a reusable organization-user directory used by #47** and by owner/evaluator/inheritance assignment endpoints.
- **#38 should land before production scheduling is tuned**, otherwise operators cannot trust that `.env` values reached Spring.
- **#39 should follow #38** so the production deployment consumes the finalized configuration contract.
- **#46 is not a final cleanup step**; add tests during every P0/P1 fix.

## Release gate proposal

Before declaring a production-ready release, require all of the following:

- #32–#35 closed with regression tests;
- #37–#41 closed or formally risk-accepted with operational controls;
- production deployment uses `prod`/`production` profile and non-dev Keycloak (#39);
- cross-role Playwright tests prove assignment/resource boundaries for owner, cadastre/map, inheritance, reminders and deal evaluation;
- integration tests prove no cursor advancement on partial/truncated fetches;
- canonical active ownership relation is the basis for current-owner authorization/display/signals;
- documented restore/recovery exercises cover Postgres and contract/object storage;
- CI branch protection requires the `Integration Gate` status on `main`.
