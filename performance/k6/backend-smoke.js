import http from "k6/http";
import exec from "k6/execution";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:8080";
const token = __ENV.TOKEN || "";
const mode = __ENV.MODE || "api";
const cadastralCode = __ENV.CADASTRAL_CODE || "99999:000:0001";
const ownerSearch = __ENV.OWNER_SEARCH || "";
const relationSearch = __ENV.RELATION_SEARCH || ownerSearch;
const tilePath = __ENV.TILE_PATH || "/api/v1/map/land-parcels/8/145/75.pbf";

const ownerLegacyLatency = new Trend("forestiq_owner_legacy_ms", true);
const ownerCursorLatency = new Trend("forestiq_owner_cursor_ms", true);
const relationLegacyLatency = new Trend("forestiq_relation_legacy_ms", true);
const relationCursorLatency = new Trend("forestiq_relation_cursor_ms", true);
const parcelDetailLatency = new Trend("forestiq_parcel_detail_ms", true);
const integrationAdminLatency = new Trend("forestiq_integration_admin_ms", true);
const coldTileLatency = new Trend("forestiq_tile_cold_ms", true);
const warmTileLatency = new Trend("forestiq_tile_warm_ms", true);

function makeOptions() {
  if (mode === "tile-cold") {
    return {
      scenarios: {
        cold_tiles: {
          executor: "constant-vus",
          exec: "coldTiles",
          vus: Number(__ENV.TILE_VUS || 2),
          duration: __ENV.TILE_DURATION || "20s",
        },
      },
      thresholds: {
        http_req_failed: ["rate<0.01"],
        forestiq_tile_cold_ms: ["p(95)<500", "p(99)<1000"],
      },
    };
  }
  if (mode === "tile-warm") {
    return {
      scenarios: {
        warm_tiles: {
          executor: "constant-vus",
          exec: "warmTiles",
          vus: Number(__ENV.TILE_VUS || 5),
          duration: __ENV.TILE_DURATION || "20s",
        },
      },
      thresholds: {
        http_req_failed: ["rate<0.01"],
        forestiq_tile_warm_ms: ["p(95)<100", "p(99)<250"],
      },
    };
  }
  return {
    scenarios: {
      api_reads: {
        executor: "constant-vus",
        exec: "apiReads",
        vus: Number(__ENV.READ_VUS || 10),
        duration: __ENV.READ_DURATION || "45s",
      },
    },
    thresholds: {
      http_req_failed: ["rate<0.01"],
      forestiq_owner_cursor_ms: ["p(95)<300", "p(99)<600"],
      forestiq_relation_cursor_ms: ["p(95)<400", "p(99)<800"],
      forestiq_parcel_detail_ms: ["p(95)<300", "p(99)<600"],
      forestiq_integration_admin_ms: ["p(95)<300", "p(99)<600"],
    },
  };
}

export const options = makeOptions();

function headers(accept = "application/json") {
  const result = { Accept: accept };
  if (token) result.Authorization = `Bearer ${token}`;
  return result;
}

function record(response, trend, name, expectedStatus = 200) {
  trend.add(response.timings.duration);
  check(response, {
    [`${name}: expected status`]: (r) => r.status === expectedStatus,
    [`${name}: authenticated`]: (r) => token ? r.status !== 401 && r.status !== 403 : true,
  });
}

function searchParam(value) {
  return encodeURIComponent(value || "");
}

export function apiReads() {
  const legacyOwner = http.get(
    `${baseUrl}/api/v1/owners?search=${searchParam(ownerSearch)}&page=0&size=50`,
    { headers: headers(), tags: { endpoint: "owner-legacy" } },
  );
  record(legacyOwner, ownerLegacyLatency, "legacy owner search");

  const cursorOwner = http.get(
    `${baseUrl}/api/v1/owners/cursor?search=${searchParam(ownerSearch)}&size=50`,
    { headers: headers(), tags: { endpoint: "owner-cursor" } },
  );
  record(cursorOwner, ownerCursorLatency, "cursor owner search");

  const legacyRelation = http.get(
    `${baseUrl}/api/v1/ownership-relations?search=${searchParam(relationSearch)}&limit=50`,
    { headers: headers(), tags: { endpoint: "relation-legacy" } },
  );
  record(legacyRelation, relationLegacyLatency, "legacy relation search");

  const cursorRelation = http.get(
    `${baseUrl}/api/v1/ownership-relations/cursor?search=${searchParam(relationSearch)}&size=50`,
    { headers: headers(), tags: { endpoint: "relation-cursor" } },
  );
  record(cursorRelation, relationCursorLatency, "cursor relation search");

  const detail = http.get(
    `${baseUrl}/api/v1/map/land-parcels/${encodeURIComponent(cadastralCode)}/summary`,
    { headers: headers(), tags: { endpoint: "parcel-summary" } },
  );
  record(detail, parcelDetailLatency, "parcel detail");

  const integrations = http.get(`${baseUrl}/api/v1/admin/integrations`, {
    headers: headers(),
    tags: { endpoint: "integration-admin" },
  });
  record(integrations, integrationAdminLatency, "integration admin");

  sleep(0.15);
}

function coldTileCandidates() {
  const configured = (__ENV.TILE_COLD_PATHS || "").split(",").map(v => v.trim()).filter(Boolean);
  if (configured.length > 0) return configured;

  const match = tilePath.match(/^(.*\/)(\d+)\/(\d+)\/(\d+)\.pbf(?:\?.*)?$/);
  if (!match) throw new Error("TILE_COLD_PATHS is required when TILE_PATH is not a canonical XYZ .pbf path");
  const prefix = match[1];
  const z = Number(match[2]);
  const baseX = Number(match[3]);
  const baseY = Number(match[4]);
  const span = 2 ** z;
  return Array.from({ length: 32 }, (_, index) => {
    const x = (baseX + (index % 8)) % span;
    const y = (baseY + Math.floor(index / 8)) % span;
    return `${prefix}${z}/${x}/${y}.pbf`;
  });
}

function tileUrl(path) {
  return `${baseUrl}${path}`;
}

function recordTile(response, trend, name) {
  record(response, trend, name);
  check(response, {
    [`${name}: bounded payload`]: (r) => Number(r.headers["Content-Length"] || r.body?.length || 0) < 2_000_000,
    [`${name}: non-empty payload`]: (r) => Number(r.headers["Content-Length"] || r.body?.length || 0) > 0,
  });
}

export function coldTiles() {
  // Cold samples rotate through distinct XYZ identities. Query-string versions
  // are intentionally forbidden because MapTileService ignores them for cache identity.
  const candidates = coldTileCandidates();
  const path = candidates[Number(exec.scenario.iterationInTest || 0) % candidates.length];
  const tile = http.get(tileUrl(path), {
    headers: headers("application/vnd.mapbox-vector-tile"),
    tags: { endpoint: "protected-mvt-cold" },
  });
  recordTile(tile, coldTileLatency, "cold tile");
  sleep(0.05);
}

export function warmTiles() {
  const tile = http.get(tileUrl(tilePath), {
    headers: headers("application/vnd.mapbox-vector-tile"),
    tags: { endpoint: "protected-mvt-warm" },
  });
  recordTile(tile, warmTileLatency, "warm tile");
  sleep(0.05);
}
