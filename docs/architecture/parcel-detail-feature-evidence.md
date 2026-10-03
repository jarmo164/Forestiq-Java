# Parcel detail feature completion evidence

Feature: #328

All planned child issues #329–#337 are completed in the repository. The parcel detail implementation now has regression coverage for the architectural behaviors that must not regress while the workspace evolves.

## Locked behaviors

- Fast overview remains bounded and does not render bulk detail rows.
- Missing values remain distinct from measured zero.
- Owner relations preserve multi-owner semantics in the read model.
- External-data sections expose source/provenance, generation and freshness metadata.
- Section failures remain local and explicitly tell the user that the rest of the parcel workspace remains usable.
- Failed sections offer a retry action.
- Large/optional sections use progressive disclosure instead of blocking initial overview rendering.
- Map/detail geometry selection remains a separate transient interaction from canonical parcel data.

## Regression suites

Relevant frontend tests and static boundary guards include:

- `frontend/components/map/map-parcel-panel.test.tsx`
- `frontend/components/map/map-parcel-panel-shared.test.tsx`
- `frontend/components/map/map-parcel-panel-utils.test.ts`
- `frontend/scripts/check-parcel-detail-boundary.mjs`

The read-model and performance/failure contracts remain documented in:

- `docs/architecture/parcel-detail-read-model.md`

## Feature closure

The repository implementation is complete and all planned child issues #329–#337 are closed.

The former production-shaped performance acceptance is now owned by #208, which explicitly requires representative live telemetry. Role/device usability evidence is owned by #305. Keeping those operational research gates in their dedicated issues avoids holding the parcel-detail implementation epic open while also avoiding fabricated evidence.

#328 can therefore close as the implementation feature. Future parcel-detail changes must keep the bounded read-model guard and regression suites green.
