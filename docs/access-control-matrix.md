# ForestIQ access-control matrix

This document defines the resource/action policy for owner-derived CRM data. Organization isolation is always enforced first from the authenticated `organization_id` claim.

## Roles

- `ORG_ADMIN`: organization-wide administration and CRM access.
- `CRM_MANAGER`: organization-wide CRM management access.
- `CALLER`: assignment-scoped sales work. If a token contains `CALLER`, assignment scoping remains active even when it also contains `VIEWER` or `EVALUATOR`. Only `ORG_ADMIN` or `CRM_MANAGER` may override that boundary.
- `EVALUATOR`: organization-wide evaluation work unless the same token also contains `CALLER`; in that multi-role case the caller assignment boundary wins.
- `VIEWER`: read-only organization access unless the same token also contains `CALLER`; in that multi-role case the caller assignment boundary wins.

## Resource × action matrix

| Resource / action | ORG_ADMIN | CRM_MANAGER | CALLER | EVALUATOR | VIEWER |
| --- | --- | --- | --- | --- | --- |
| Read owner/contact | Organization | Organization | Assigned only | Organization | Organization |
| Edit owner/contact | Organization | Organization | Assigned only | No | No |
| Change owner status | Organization | Organization | Assigned only | No | No |
| Change assignment | Yes | Yes | No | No | No |
| Read parcel/map summary | Organization | Organization | Assigned only | Organization | Organization |
| Read map vector tile | Organization | Organization | Assigned parcels only | Organization | Organization |
| Read cadastre labels/evaluation/notifications/features | Organization | Organization | Assigned only | Organization | Organization |
| Edit cadastre label/evaluation | Organization | Organization | No direct endpoint | Organization | No |
| Read deal brief/history | Organization | Organization | Assigned only | Organization | No |
| Create deal / start negotiation | Organization | Organization | Assigned only | No | No |
| Read evaluator queue | Organization | Organization | Only assigned-owner tasks for multi-role CALLER tokens | Organization | No |
| Submit evaluation | Organization | Organization | Only when endpoint is available through another role and owner is assigned | Organization | No |

`Organization` means any resource belonging to the active organization. It never means cross-organization access.

## Multi-role rule

Permissions are not merged in a way that broadens a `CALLER` resource boundary. A token with `CALLER+VIEWER` or `CALLER+EVALUATOR` remains assignment-scoped for owner-derived resources and mutations. `ORG_ADMIN` and `CRM_MANAGER` are the explicit organization-wide override roles.

## Map decision

CALLER users do not receive non-assigned parcels in map vector tiles. This intentionally avoids leaking owner status or assignment metadata and keeps map behavior aligned with owner, cadastre and deal authorization. Parcel summaries use the same assignment restriction before owner name, phone or address is returned.

## Enforcement points

- `OwnerAuthorizationService` owns the assignment-boundary rule and multi-role semantics.
- Owner services enforce read/mutate/assignment actions.
- Map queries receive the caller assignment restriction and filter before data is returned.
- Cadastre endpoints resolve the parcel owner assignment and authorize before reading or writing resource details.
- Deal reads and mutations resolve the owner assignment; evaluator queue queries also receive the caller restriction.

When new owner-derived endpoints are added, they must use the same authorization service rather than relying only on controller role annotations.
