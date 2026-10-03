# Completed umbrella issues — 2026-09-08

This note records umbrella issues whose implementation work is already present in `main`, but whose parent issue remained open after the concrete child issues were completed.

## Closed in this cleanup

| Issue | Status evidence | Result |
| --- | --- | --- |
| #210 — Kriitilise töökindluse ja kontrollitava kasutajahalduse taastamine | #213, #214 and #215 are completed | User-deletion prechecks, race-safe deletion workflow and authenticated E2E bootstrap are delivered. |
| #211 — Töövoo andmetervikluse ja kliendi oleku kooskõla | #216, #217, #218 and #219 are completed | Owner status transitions, inheritance concurrency/editing and deal API contract cleanup are delivered. |
| #260 — Unified Map Layer Registry | #270 and #271 are completed | Safe layer descriptors and grouped/persisted layer workspace are delivered. |
| #261 — Forestry spatial and operational contract | #272 and #273 are completed | CRS/axis-order sanity checks plus attribution/freshness/failure semantics are delivered. |
| #255 — Metsaregister hybrid map/data layer architecture | #256–#261 are completed | Metsaregister layer classification, map gateway, approved local ingestion, bounded spatial delivery, layer registry and operational semantics are delivered. |

## Backlog hygiene rule

Parent/feature/epic issues should be closed when all required implementation child issues are completed and the stated acceptance criteria are represented by the merged implementation or documentation. Checkbox text in older issue bodies is not treated as authoritative when the referenced child issue itself is already closed as `completed`.

## Still intentionally open

- #208 remains post-launch work and requires representative production telemetry.
- #305 remains discovery work and requires real moderated/asynchronous user sessions.
- #254/#233 remain terrain/elevation research and implementation work until their ADR/prototype acceptance criteria are met.
- #225 remains open while the optional terrain/elevation branch (#233/#254) is still open, unless the epic scope is explicitly redefined to make that feature non-blocking for closure.
