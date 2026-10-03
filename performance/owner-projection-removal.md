# `land_parcel.owner_id` compatibility projection inventory

`owner_parcel_relation` is the canonical ownership model. The old nullable
`land_parcel.owner_id` field cannot represent co-ownership and must not be used
for current ownership decisions.

Repository review before removing the row-level projection trigger:

- `OwnerParcelRepository` counts and loads parcels through active
  `owner_parcel_relation` rows.
- `CadastreRepository.findAssignmentScope` resolves assigned subjects through
  active relations.
- `MapTileRepository` resolves visible/current owner attributes through active
  relations rather than `land_parcel.owner_id`.
- `OwnerParcelRelationService` reads and writes the canonical relation table.
- the interactive owner and relation cursor read paths use the relation table.
- `OwnerQueryRepository` still declares a legacy `P_OWNER` field constant, but
  active query paths do not use it as ownership authority.
- generated jOOQ classes still contain the physical legacy column, which is why
  this migration intentionally leaves the nullable column in place for one
  compatibility cycle.

## Current release

`V27__remove_owner_projection_trigger.sql` removes the row-level trigger and its
functions. The remaining column is explicitly documented as **not maintained**.
This removes the per-relation lookup + parcel UPDATE write amplification from
bulk and OLTP relation changes.

## Follow-up cleanup

After generated schema/client compatibility is refreshed, remove the unused
`P_OWNER` declaration, drop `land_parcel.owner_id`, regenerate jOOQ sources and
verify migrations/tests against a clean database. No new code may read or write
the legacy field in the meantime.
