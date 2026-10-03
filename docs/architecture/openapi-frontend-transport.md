# ADR: OpenAPI-generated frontend transport

**Status:** Accepted for new and migrated JSON API calls  
**Issue:** #390  
**Related:** #83, #100, #388

## Context

ForestIQ already generates `frontend/lib/generated/openapi.d.ts` and
`frontend/lib/generated/client.ts`, but feature modules historically also
constructed API URLs and DTO assumptions by hand.

That creates two independent contracts:

1. Spring controllers and the generated OpenAPI document;
2. handwritten frontend URL strings, request shapes and response types.

The refreshed contract now includes the decision-support workbasket and
deal-evaluation assignment endpoints that previously required temporary
handwritten transport exceptions.

## Decision

For JSON endpoints represented in OpenAPI, frontend feature modules use
`frontend/lib/api/transport.ts`, which delegates to the generated client.

The generated client remains layered on top of the existing `apiFetch`
transport. Therefore the migration does **not** move authentication into feature
modules and does not bypass the Next.js BFF. Cookie/session behavior, normalized
API errors and BFF security controls remain in the existing transport layer.

Feature code supplies only:

- the OpenAPI path template;
- typed path/query parameters;
- the typed request body where applicable.

Feature code must not create a second DTO or handwritten URL for an operation
that is already available through the generated client.

## CI contract

The `OpenAPI contract / generated client` CI job starts the Spring API against
ephemeral PostGIS, downloads `/api/openapi`, regenerates the TypeScript
artifacts and fails when the committed generated files differ.

On failure, CI retains the generated OpenAPI JSON and TypeScript files for 14
days. This makes contract drift directly actionable instead of requiring a
developer to reconstruct the generated result manually.

The final `quality-gate` depends on this contract job.

## Migration order

The pilot migration covers owner/workdesk, owner-360/ownership relations,
workbasket/handoff/decision-support, deal-work and commercial deal JSON
operations represented in the committed OpenAPI document. Responses whose
OpenAPI fields are optional but are UI invariants are validated at the feature
API boundary before being exposed as stricter UI types.

Nested Java request types that share the same simple class name must declare a
unique OpenAPI schema name. The deal-work and commercial loss commands use
`DealWorkLoseDealRequest` and `CommercialLoseDealRequest` respectively so
schema generation cannot silently bind one endpoint to the other command
shape.

Multipart owner import and binary/CSV download flows keep specialized transport
helpers because the generic JSON client is not the correct abstraction. These
exceptions must remain explicit and must not be used for ordinary JSON APIs.

## Consequences

Positive:

- backend path/body/response changes become compile- or CI-visible;
- BFF authentication and error behavior stay centralized;
- feature modules lose duplicate transport DTOs and URL construction over time.

Trade-offs:

- backend OpenAPI generation becomes a required build dependency;
- generated artifacts must be committed and refreshed together;
- temporary manual exceptions remain visible until their backend operations
  appear in the generated specification.

## Rule for new endpoints

A new JSON endpoint is not complete until:

1. it appears in the generated OpenAPI document;
2. generated frontend artifacts are committed;
3. at least one frontend integration uses the generated transport, or an
   explicit documented exception explains why there is no frontend consumer.


## Migration ratchet

The pilot modules are protected by `frontend/scripts/check-api-transport-boundary.mjs`.

The check requires all migrated owner, ownership-relation, decision-support,
workbasket and deal modules to have zero raw JSON `apiFetch`/direct `fetch`
calls. It also scans the whole feature API directory for raw URLs in these
protected domains, so adding a new module does not bypass the ratchet. The only
current owner-domain direct-fetch exception is the CSV error download; multipart
file upload helpers remain specialized non-JSON transport. CI/local lint fails
if a protected domain reintroduces a raw JSON URL.

Exception counts are a one-way ratchet: new JSON endpoints must use
`apiTransport`.
