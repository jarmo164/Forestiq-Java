# MapLibre overview/detail browser checklist (2026-09-01)

Status: FAIL

## Checklist

- Overview -> detail transition: BLOCKED
- Zoom thresholds (overview z6-z10, detail z11+): BLOCKED
- Parcel focus/select behavior: BLOCKED
- Assignment/organization visibility behavior: BLOCKED

## Blocker evidence

- Browser callback body text: `{"detail":"Login service unavailable"}`
- URL observed: `http://localhost:3000/auth/callback?...`

Without an authenticated working UI session, manual browser acceptance behavior cannot be verified.
