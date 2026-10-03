# Feature #328 contract evidence

Status: review evidence for the replacement pull request from `feature/328-parcel-complete-detail-v2`.

## Contract boundary

The fast overview remains `GET /api/v1/map/land-parcels/{cadastralCode}/summary`. It returns scalar cadastral facts, a bounded active-owner summary, counts, and the published cadastre generation/date. It does not return notification history, raw source payloads, or polygon collections.

Large sections stay independently bounded:

| Section | Endpoint shape | Bound |
| --- | --- | --- |
| Notifications | `/api/v1/cadastres/{code}/notifications/page?cursor=&limit=` | keyset page, server cap 100 |
| Forest features | `/api/v1/cadastres/{code}/forest-features/page?cursor=&limit=` | keyset page, server cap 100 |
| Sub-parts | `/api/v1/cadastres/{code}/sub-parts/page?cursor=&limit=` | keyset page, server cap 100 |
| Sub-part geometry | `/api/v1/cadastres/{code}/sub-parts/{id}/geometry` and `.../by-code/{code}/geometry` | one selected geometry |
| GIS facts | `/api/v1/map/land-parcels/{code}/context` | enabled manifest layers only |

All tenant-restricted paths authorize before repository access. Nested co-owner summaries intentionally exclude contact PII; the separately authorized primary owner projection retains the existing call-workflow fields.

## Executable evidence

| Requirement | Evidence |
| --- | --- |
| Published generation and bounded summary counts | `GenerationMapTileRepositoryIntegrationTest` |
| Multi-owner canonical semantics | `GenerationMapTileRepositoryIntegrationTest.summaryPreservesMultiOwnerSemanticsAndBoundedSectionCounts` |
| Assignment-scoped overview | `MapTileControllerAuthorizationTest` |
| Bounded page contracts | `CadastreControllerAuthorizationTest` |
| By-code geometry authorization | `CadastreControllerAuthorizationTest.subPartGeometryByCodeAuthorizesBeforeReadingTenantScopedGeometry` |
| Full/partial/large overview rendering | `map-parcel-panel.test.tsx` |
| Geometry parser fail-closed behavior | `detail-geometry.test.ts` |
| Programmatic context-layer reveal | `metsaregister-overlay-control.test.ts` |
| Multi-owner → sub-part → notification → GIS/source → close flow | `map-first.spec.ts` |
| Section-local 503 handling | `map-first.spec.ts` |

## Verification commands

```bash
cd backend && ./gradlew test
cd frontend && pnpm test && pnpm typecheck && pnpm lint
cd frontend && pnpm test:e2e:playwright --grep "complete detail|GIS section failure"
```

## Performance evidence boundary

The implementation makes response size independent of notification, sub-part, and raw-feature history by returning counts in the overview and loading detail via capped keyset pages. The integration and component tests verify that contract. A production-shaped latency result must still be captured in the normal benchmark environment before #328 and its children are closed; this document does not claim a synthetic CI timing as production evidence.
