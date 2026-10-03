// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Map as MapLibreMap } from "maplibre-gl";
import { MetsaregisterOverlayControl } from "./metsaregister-overlay-control";

const mixedRegistry = {
  manifestVersion: "2026-09-07",
  tileCatalogVersion: "2026-09-07",
  forestryGenerationId: "11111111-1111-1111-1111-111111111111",
  layers: [
    {
      key: "metsaregister:teatis",
      title: "Kehtivad metsateatised",
      provider: "Metsaregister",
      category: "FORESTIQ_FORESTRY",
      businessMode: "LOCAL_DOMAIN_DATA",
      transport: "MVT",
      sourceLayer: "forestry",
      minZoom: 9,
      maxZoom: 20,
      defaultOpacity: 0.7,
      order: 10,
      defaultVisible: true,
      queryable: true,
      mandatory: false,
      attribution: "Metsaregister / Keskkonnaagentuur",
      freshnessTarget: "PT6H",
      authorizationScope: "AUTHENTICATED",
      styleKind: "FORESTRY_POLYGON",
      available: true,
      tileUrlTemplate: "/api/v1/map/metsaregister/mvt/metsaregister:teatis/11111111-1111-1111-1111-111111111111/{z}/{x}/{y}.pbf",
    },
    {
      key: "external:wms",
      title: "WMS overlay",
      provider: "External",
      category: "METSAREGISTER_EXTERNAL",
      businessMode: "EXTERNAL_MAP",
      transport: "WMS",
      sourceLayer: null,
      minZoom: 7,
      maxZoom: 20,
      defaultOpacity: 0.5,
      order: 100,
      defaultVisible: true,
      queryable: false,
      mandatory: false,
      attribution: "External WMS",
      freshnessTarget: "PROVIDER",
      authorizationScope: "AUTHENTICATED",
      styleKind: "RASTER",
      available: true,
      tileUrlTemplate: "/api/v1/map/metsaregister/wms/external:wms?bbox={bbox-epsg-3857}",
    },
    {
      key: "external:wmts",
      title: "WMTS overlay",
      provider: "External",
      category: "METSAREGISTER_EXTERNAL",
      businessMode: "EXTERNAL_MAP",
      transport: "WMTS",
      sourceLayer: null,
      minZoom: 7,
      maxZoom: 20,
      defaultOpacity: 0.5,
      order: 101,
      defaultVisible: true,
      queryable: false,
      mandatory: false,
      attribution: "External WMTS",
      freshnessTarget: "PROVIDER",
      authorizationScope: "AUTHENTICATED",
      styleKind: "RASTER",
      available: true,
      tileUrlTemplate: "/api/v1/map/external/wmts/safe-layer/{z}/{x}/{y}.png",
    },
  ],
};

const contextLayers = [
  {
    sourceKey: "MARU_ETAK_WOODY",
    title: "ETAK - puittaimestik",
    datasetFamily: "ETAK",
    geometryType: "POLYGON",
    minZoom: 11,
    maxZoom: 16,
    defaultOpacity: 0.28,
    caveat: "Topograafiline maakate.",
    generationId: "22222222-2222-2222-2222-222222222222",
    tileUrlTemplate: "/api/v1/map/context/mvt/MARU_ETAK_WOODY/22222222-2222-2222-2222-222222222222/{z}/{x}/{y}.pbf",
    available: true,
  },
  {
    sourceKey: "MARU_ETAK_ROADS",
    title: "ETAK - teed",
    datasetFamily: "ETAK",
    geometryType: "LINE",
    minZoom: 10,
    maxZoom: 16,
    defaultOpacity: 0.45,
    caveat: "Topograafiline võrgustik.",
    generationId: "33333333-3333-3333-3333-333333333333",
    tileUrlTemplate: "/api/v1/map/context/mvt/MARU_ETAK_ROADS/33333333-3333-3333-3333-333333333333/{z}/{x}/{y}.pbf",
    available: true,
  },
];

beforeEach(() => window.localStorage.clear());
afterEach(() => {
  vi.unstubAllGlobals();
  document.body.replaceChildren();
});

function fakeMap() {
  const sources = new Set<string>();
  const layers = new Set<string>(["parcels-overview", "parcels-fill", "parcels-line", "parcels-selected", "parcels-label"]);
  const calls = {
    addSource: [] as Array<{ id: string; source: unknown }>,
    moveLayer: [] as Array<{ id: string; before?: string }>,
  };
  const map = {
    on: vi.fn(() => map),
    off: vi.fn(() => map),
    getSource: vi.fn((id: string) => sources.has(id) ? ({}) : undefined),
    addSource: vi.fn((id: string, source: unknown) => { sources.add(id); calls.addSource.push({ id, source }); return map; }),
    removeSource: vi.fn((id: string) => { sources.delete(id); return map; }),
    getLayer: vi.fn((id: string) => layers.has(id) ? ({ id }) : undefined),
    addLayer: vi.fn((layer: { id: string }, _before?: string) => { layers.add(layer.id); return map; }),
    removeLayer: vi.fn((id: string) => { layers.delete(id); return map; }),
    moveLayer: vi.fn((id: string, before?: string) => { calls.moveLayer.push({ id, before }); return map; }),
    setPaintProperty: vi.fn(() => map),
  };
  return { map: map as unknown as MapLibreMap, calls };
}

async function settle() {
  await new Promise(resolve => setTimeout(resolve, 0));
}

describe("MetsaregisterOverlayControl ordering and mixed transport contract", () => {
  it("renders MVT, WMS and WMTS descriptors through one workspace contract", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      return Promise.resolve({ ok: true, json: async () => url.includes("/context/layers") ? [] : mixedRegistry });
    }));
    const { map, calls } = fakeMap();
    const control = new MetsaregisterOverlayControl("token");
    const element = control.onAdd(map);
    document.body.appendChild(element);
    await settle();

    const sourceTypes = calls.addSource.map(call => (call.source as { type?: string }).type);
    expect(sourceTypes).toContain("vector");
    expect(sourceTypes.filter(type => type === "raster")).toHaveLength(2);
    expect(element.textContent).toContain("MVT");
    expect(element.textContent).toContain("WMS");
    expect(element.textContent).toContain("WMTS");
    control.onRemove();
  });

  it("keeps the layer list minimal while preserving descriptor order", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      return Promise.resolve({ ok: true, json: async () => url.includes("/context/layers") ? contextLayers : mixedRegistry });
    }));
    const first = fakeMap();
    const control = new MetsaregisterOverlayControl("token");
    const element = control.onAdd(first.map);
    document.body.appendChild(element);
    await settle();

    const rows = [...element.querySelectorAll<HTMLElement>('[data-layer-key]')]
      .map(row => row.dataset.layerKey);
    expect(rows).toEqual([
      "MARU_ETAK_WOODY",
      "MARU_ETAK_ROADS",
      "metsaregister:teatis",
      "external:wms",
      "external:wmts",
    ]);
    expect(element.querySelector('[aria-label="ETAK - puittaimestik nähtavus"]')).toBeTruthy();
    expect(element.querySelector('[aria-label="ETAK - puittaimestik info"]')).toBeTruthy();
    expect(element.querySelector('[aria-label="ETAK - puittaimestik värv kaardil"]')).toBeTruthy();
    expect(element.querySelector('[aria-label="Liiguta kihti üles"]')).toBeNull();
    expect(element.querySelector('[aria-label="Liiguta kihti alla"]')).toBeNull();
    expect(element.textContent).not.toContain("Manifest");
    expect(element.textContent).not.toContain("ETAK kontekst");
    expect(element.textContent).not.toContain("Metsaregistri välised kihid");
    control.onRemove();
  });
});
