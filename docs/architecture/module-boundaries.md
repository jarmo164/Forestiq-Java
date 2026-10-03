# Backend Module Boundaries

Status: accepted  
Issue: #395

ForestIQ backend is a modular monolith. The first package below `ee.forestiq` is the domain boundary unless this document states otherwise.

## General Rules

- Controllers expose HTTP/API contracts and call application/domain services in their own module.
- Controllers must not call repositories from another domain directly.
- Repositories are persistence adapters and must not depend on controllers.
- Cross-domain reads should use an explicit public service/port or a shared value object.
- Provider ingestion code must stay behind the integration boundary and must not depend on UI controllers or Keycloak admin operations.
- Package moves should happen incrementally; do not rename broad package trees only to satisfy a boundary rule.

## Domain Map

| Domain | Public boundary | May depend on | Must not depend on |
| --- | --- | --- | --- |
| `owner` | owner services and DTOs | `security`, `workflow`, own repositories | foreign repositories directly |
| `deal` | deal services and workflow methods | `owner` public services, `security`, `contract` public ports | owner/deal persistence shortcuts from controllers |
| `contract` | contract services and storage ports | `deal` public contract source, `security` | controllers or raw owner/deal repositories outside public ports |
| `workflow` | workflow services | `owner`, `deal`, `reminder` public services | HTTP controllers |
| `registry` | registry map/search/freshness services | `integration`, `geospatial` public services, jOOQ internally | UI/controller layer and Keycloak admin |
| `geospatial` | parcel context, generation and source services | `integration`, `cadastre`, jOOQ internally | UI/controller layer and Keycloak admin |
| `integration` | durable job/run contract and admin status service | domain job handlers, `security` context | provider-specific credentials outside handler/runtime ownership |
| `security` | organization/user context and authorization helpers | Spring Security | feature repositories and controllers |

## Registry and Geospatial Public Service Boundary

`registry` and `geospatial` are allowed to own their internal tables, jOOQ queries and provider adapters. Other domains should call public services such as:

- `ParcelGeospatialContextService`
- `GeospatialOverlayService`
- `GeospatialSourceGenerationRepository` only from geospatial/integration-owned code
- `RegistryFreshnessService`
- `MetsaregisterLayerManifestService`
- `MetsaregisterForestryGenerationService`

New cross-domain needs should become a small public method or value object rather than a direct dependency on an internal repository.

## Automated Rules

`ModuleBoundaryArchTest` enforces the initial build-time guardrails:

- repositories do not depend on controllers;
- controllers do not depend on repositories from another domain;
- registry/geospatial/inheritance/ownership/portfolio ingestion-side code does not depend on controllers or `KeycloakAdminService`.

These rules intentionally start with high-signal violations. Tighten them in small follow-up PRs as existing cross-domain ports become clearer.
