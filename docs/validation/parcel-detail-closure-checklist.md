# Parcel Detail Epic Closure Checklist

Status: implementation delivered, validation evidence pending

## Required Evidence Before Closing #328

| Evidence | Required proof | Status |
| --- | --- | --- |
| Unified read model/API | Parcel detail consumers use one detail contract instead of ad hoc panel queries | Pending verification |
| E2E map to parcel detail | Selecting a parcel from map opens overview and progressive sections | Pending test run |
| Multi-owner parcel | Detail shows all owners and CRM/workflow context correctly | Pending fixture/test evidence |
| Compartment interaction | Forestry compartments can be inspected without losing parcel context | Pending test run |
| Forest notification history | Active/history notifications render with spatial context | Pending test run |
| GIS parcel facts | Approved layer facts appear with source/freshness | Pending test run |
| Partial failure | One section failure does not break the whole detail panel | Pending test run |
| Map/detail selection sync | Selected subobject remains synchronized between map and detail | Pending test run |
| Accessibility | Keyboard/focus path does not regress #306/#288 patterns | Pending audit |
| Performance | Overview loads without waiting for heavy sections; large sections are bounded | Pending benchmark |

## Regression Fixture Targets

| Fixture | Why it matters |
| --- | --- |
| Single-owner parcel with no forestry data | Missing values are not shown as zero |
| Multi-owner parcel | Ownership list and CRM context do not collapse owners |
| Parcel with active notification | Notification summary and spatial highlight are visible |
| Parcel with archived notification | History is available without crowding overview |
| Parcel with multiple compartments | Section drill-down and map sub-selection stay linked |
| Parcel with one failed source section | Detail remains usable and failure is local |

## Closure Rule

Do not close #328 solely from merged implementation PRs. Close only after the validation evidence above is linked from the issue or a test report.
