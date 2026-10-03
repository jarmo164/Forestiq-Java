# Backend workflow service boundaries

The rewrite keeps transaction ownership in application/use-case services while separating database persistence and external-provider adapters. The goal is not to hide jOOQ behind generic repositories; each extracted component exists because it owns a concrete technical responsibility.

## Module ownership and public surface

ForestIQ remains one deployable modular monolith. The current package layout is evolved in place; a package does not become a public API merely because Java can import it.

| Module/package | Owns | Public application-facing surface | Must not own/import |
| --- | --- | --- | --- |
| `security` | authenticated subject, organization context, role conversion | `OrganizationContext`, authorization services | business persistence/provider orchestration |
| `owner` | owner CRM state, assignment, owner/parcel access policy | `OwnerService`, authorization/read DTOs intentionally exposed to callers | registry/provider clients, deal repository internals |
| `cadastre` | parcel read models and protected map/tile queries | map/parcel query endpoints and DTOs | owner aggregate mutation rules |
| `deal` | qualification, evaluation and commercial workflow | `DealService`, `DealCommercialService` | inheritance/registry/portfolio repository internals |
| `inheritance` | inheritance cases/heirs and official-notice workflow | `InheritanceService` | deal/registry/ownership repository internals |
| `contract` | contract/template/document generation workflow | contract application services | unrelated CRM repositories |
| `messaging` / `reminder` | bounded communication/reminder workflows | their application services/controllers | registry/provider adapters |
| `registry` | public-registry ingestion/read state and freshness | registry application services/handlers | deal/inheritance internals |
| `portfolio` | MetsIS portfolio ingestion | integration handler/application service | HTTP/controller concerns |
| `ownership` | ownership-transition ingestion | integration handler/application service | HTTP/controller concerns |
| `integration` | durable run state, scheduling/execution protocol, health | `IntegrationJobService`, `IntegrationJobHandler` contract | concrete business workflow internals |
| `storage` | object-storage adapter and fallback mechanics | storage service/port | business authorization decisions |
| `admin` | privileged administrative use cases | admin services/controllers | implicit cross-tenant reads outside explicit admin paths |
| `web` / `config` | cross-cutting HTTP error/request plumbing and runtime config | error/request primitives only | business workflow orchestration |

### Dependency direction

Within a business module the intended direction is:

```text
controller/web -> application service -> domain policy -> persistence/provider port -> adapter
```

The current codebase does not need a package rename-only refactor to satisfy this. Instead, migrated high-risk paths enforce the semantic boundary with services/repositories/adapters and ArchUnit rules. New code must follow the direction even when existing package names are flatter than the target layout.

Cross-module access rules:

1. call an application-facing service/interface, an owned event, or a purpose-built read model;
2. do not import another module's repository implementation to perform business orchestration;
3. integration handlers live with the provider/business module and implement the `integration` contract; the integration runner must not depend back on those modules;
4. organization scope is passed/resolved explicitly and may not be inferred from arbitrary request data;
5. persistence entities are not cross-module/API contracts.

`BackendArchitectureTest` is the CI guard for the migrated dependency rules. Add an ArchUnit rule whenever a new stable boundary would otherwise rely only on review convention.

## Shared kernel

Keep cross-cutting shared code deliberately small. Suitable shared concepts are:

- authenticated request/organization context;
- bounded pagination primitives;
- common API problem/correlation model;
- clock/time abstraction;
- stable identifiers;
- observability helpers.

Business DTOs, repositories, provider payloads and workflow rules do **not** move to a generic shared package to bypass module boundaries.

## Deal workflow

### Application services

- `DealService` owns qualification/evaluation decisions: authorization, allowed stages, evaluator assignment rules, business-day deadlines, owner status transitions and conflict semantics.
- `DealCommercialService` owns the commercial state machine after valuation: versioned seller offers, customer counteroffers and terminal WON/LOST/CANCELLED transitions.

Both are transaction boundaries. They decide **what** a workflow change means and when it is allowed.

### Persistence adapters

- `DealQueryRepository` owns deal/owner/parcel/evaluation read models.
- `DealCommandRepository` owns deal/evaluation writes, row locks, reminders and immutable deal/owner audit events.

Application services no longer build SQL updates/inserts themselves. Expected-stage concurrency checks remain decided by the service and are executed atomically by the command repository.

### Characterization coverage

`DealServiceTest`, `DealAuthorizationBoundaryTest` and `DealConcurrencyTest` preserve qualification, authorization and stale-transition behavior. `DealCommercialServiceTest` covers commercial state transitions independently.

## Inheritance workflow

### Application service

`InheritanceService` owns the inheritance use case: case/heir validation, official-notice matching rules, owner-deceased workflow, terminal case semantics and publication of owner-status domain events.

It is the transaction boundary for interactive and scheduled notice checks.

### External adapter

`InheritanceOfficialNoticeClient` owns HTTP/source configuration and remote notice retrieval. It does not decide CRM status or create cases.

### Persistence adapter

`InheritanceRepository` owns owner/case/heir/sync-log reads and writes, case events, owner activities and the PostgreSQL-specific personal-code selection used by the scheduled refresh. External-source orchestration is not performed in the repository.

### Characterization coverage

`InheritanceServiceTest` exercises the same service API against H2 through the repository adapter. `InheritanceOfficialNoticeClientTest` separately covers the external adapter contract.

## Registry synchronization

### Application service

`RegistrySyncService` owns provider sequencing, source-specific error isolation, `REQUIRES_NEW` refresh transactions, freshness policy and the decision whether a changed provider record should create a data-change signal.

### External adapter

`ExternalRegistryService` owns Maa-amet/Metsaregister/RIK protocol handling, request construction, parsing and bounded retry behavior. It does not write CRM tables.

### Persistence and audit adapters

- `RegistryDataRepository` owns organization-scoped provider snapshot persistence: parcel attributes/geometry, forest notifications, forest-plan features and freshness-selection queries.
- `RegistrySyncAuditRepository` owns registry sync audit rows and idempotent data-change-signal persistence.

The repository boundary deliberately keeps provider HTTP calls and transaction retry/orchestration out of persistence code.

### Characterization coverage

`RegistrySyncServiceTest` verifies the application decision that a new notification creates a change signal while an already-known notification is replaced without duplicating that signal. `RegistryFreshnessServiceTest` covers organization-scoped freshness reporting/recovery selection.

## Transaction rule

Controllers do not own transactions. Application services do. Repositories participate in the caller transaction unless the application service explicitly uses a separate transaction policy (registry source refreshes use `REQUIRES_NEW`). External adapters never start business transactions or perform CRM orchestration.

This separation keeps business decisions testable while retaining jOOQ where SQL/geospatial/PostgreSQL behavior is the most precise representation of persistence logic.
