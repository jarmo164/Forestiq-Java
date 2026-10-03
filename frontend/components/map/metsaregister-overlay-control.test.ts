// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Map as MapLibreMap } from "maplibre-gl";
import { MetsaregisterOverlayControl } from "./metsaregister-overlay-control";

const registry = {
  manifestVersion: "2026-09-06",
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
      defaultVisible: false,
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
      key: "metsaregister:katsealad",
      title: "Katsealad",
      provider: "Metsaregister",
      category: "METSAREGISTER_EXTERNAL",
      businessMode: "EXTERNAL_MAP",
      transport: "WMS",
      sourceLayer: null,
      minZoom: 7,
      maxZoom: 20,
      defaultOpacity: 0.45,
      order: 100,
      defaultVisible: false,
      queryable: false,
      mandatory: false,
      attribution: "Metsaregister / Keskkonnaagentuur",
      freshnessTarget: "PROVIDER",
      authorizationScope: "AUTHENTICATED",
      styleKind: "RASTER",
      available: true,
      tileUrlTemplate: "/api/v1/map/metsaregister/wms/metsaregister:katsealad?bbox={bbox-epsg-3857}&width=256&height=256&crs=EPSG:3857&format=image/png",
    },
  ],
};

const contextLayers = [
  {
    sourceKey: "MARU_ETAK_WOODY", title: "ETAK - puittaimestik", datasetFamily: "ETAK", geometryType: "POLYGON",
    minZoom: 11, maxZoom: 16, defaultOpacity: 0.28,
    caveat: "Topograafiline maakate, mitte Metsaregistri metsaandmed.",
    generationId: "22222222-2222-2222-2222-222222222222", sourceDate: "2026-09-01",
    tileUrlTemplate: "/api/v1/map/context/mvt/MARU_ETAK_WOODY/22222222-2222-2222-2222-222222222222/{z}/{x}/{y}.pbf",
    available: true,
  },
  {
    sourceKey: "MARU_ADMIN_MUNICIPALITY", title: "Haldusjaotus - omavalitsus", datasetFamily: "ADMIN", geometryType: "POLYGON",
    minZoom: 7, maxZoom: 13, defaultOpacity: 0.1, caveat: "Ruumiline viitekiht.",
    available: false,
  },
];

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.body.replaceChildren();
});

function fakeMap() {
  const listeners = new Map<string, (event: unknown) => void>();
  const sources = new Set<string>();
  const layers = new Set<string>(["parcels-overview", "parcels-fill", "parcels-line", "parcels-selected", "parcels-label"]);
  const calls = {
    addSource: [] as Array<{ id: string; source: unknown }>,
    addLayer: [] as Array<{ id: string; type?: string; before?: string }>,
    paint: [] as Array<{ id: string; property: string; value: unknown }>,
    moveLayer: [] as Array<{ id: string; before?: string }>,
  };

  const map = {
    on: vi.fn((event: string, layerOrListener: string | ((value: unknown) => void), listener?: (value: unknown) => void) => {
      if (typeof layerOrListener === "string" && listener) listeners.set(`${event}:${layerOrListener}`, listener);
      else if (typeof layerOrListener === "function") listeners.set(event, layerOrListener);
      return map;
    }),
    off: vi.fn((event: string) => { listeners.delete(event); return map; }),
    getSource: vi.fn((id: string) => sources.has(id) ? ({}) : undefined),
    addSource: vi.fn((id: string, source: unknown) => {
      sources.add(id);
      calls.addSource.push({ id, source });
      return map;
    }),
    removeSource: vi.fn((id: string) => { sources.delete(id); return map; }),
    getLayer: vi.fn((id: string) => layers.has(id) ? ({ id }) : undefined),
    addLayer: vi.fn((layer: { id: string; type?: string }, before?: string) => {
      layers.add(layer.id);
      calls.addLayer.push({ id: layer.id, type: layer.type, before });
      return map;
    }),
    removeLayer: vi.fn((id: string) => { layers.delete(id); return map; }),
    moveLayer: vi.fn((id: string, before?: string) => {
      calls.moveLayer.push({ id, before });
      return map;
    }),
    setPaintProperty: vi.fn((id: string, property: string, value: unknown) => {
      calls.paint.push({ id, property, value });
      return map;
    }),
  };

  return { map: map as unknown as MapLibreMap, listeners, calls };
}

async function settle() {
  await new Promise(resolve => setTimeout(resolve, 0));
}

function visibilityButtonFor(element: HTMLElement, title: string) {
  return [...element.querySelectorAll<HTMLButtonElement>("button")]
    .find(button => button.getAttribute("aria-label") === `${title} nähtavus`)!;
}

describe("MetsaregisterOverlayControl", () => {
  it("renders descriptor groups and mixes local MVT with external WMS without recreating map state", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      return Promise.resolve({ ok: true, json: async () => url.includes("/context/layers") ? contextLayers : registry });
    }));
    const { map, calls } = fakeMap();
    const control = new MetsaregisterOverlayControl("token");
    const element = control.onAdd(map);
    document.body.appendChild(element);
    await settle();

    const layerToggle = element.querySelector<HTMLButtonElement>('[aria-label="Ava kaardikihid"]');
    expect(layerToggle).toBeTruthy();
    expect(layerToggle?.getAttribute("aria-expanded")).toBe("false");
    layerToggle?.click();
    expect(layerToggle?.getAttribute("aria-expanded")).toBe("true");
    expect(element.textContent).not.toContain("ETAK kontekst");
    expect(element.textContent).not.toContain("ForestIQ metsaandmed");
    expect(element.textContent).not.toContain("Metsaregistri välised kihid");
    expect(element.querySelector('[aria-label="Liiguta kihti üles"]')).toBeNull();
    expect(element.querySelector('[aria-label="Liiguta kihti alla"]')).toBeNull();
    expect(element.querySelector('[data-layer-key="MARU_ADMIN_MUNICIPALITY"] [hidden]')).toBeTruthy();
    expect(element.querySelector('[aria-label="Kehtivad metsateatised värv kaardil"]')).toBeTruthy();
    expect((element.querySelector('[aria-label="Kehtivad metsateatised värv kaardil"]') as HTMLElement).style.background).toBe("rgb(220, 38, 38)");
    const info = [...element.querySelectorAll<HTMLButtonElement>("button")]
      .find(button => button.getAttribute("aria-label") === "Kehtivad metsateatised info")!;
    expect(info.getAttribute("aria-expanded")).toBe("false");
    info.click();
    expect(info.getAttribute("aria-expanded")).toBe("true");

    const forestry = visibilityButtonFor(element, "Kehtivad metsateatised");
    forestry.click();
    expect(calls.addSource.some(call => call.id === "metsaregister-overlay-source:metsaregister:teatis"
      && (call.source as { type?: string }).type === "vector")).toBe(true);
    expect(calls.addLayer.map(call => call.id)).toEqual(expect.arrayContaining([
      "metsaregister-overlay:metsaregister:teatis:fill",
      "metsaregister-overlay:metsaregister:teatis:line",
      "metsaregister-overlay:metsaregister:teatis:point",
    ]));

    const external = visibilityButtonFor(element, "Katsealad");
    external.click();
    expect(calls.addSource.some(call => call.id === "metsaregister-overlay-source:metsaregister:katsealad"
      && (call.source as { type?: string }).type === "raster")).toBe(true);
    expect(calls.addLayer).toContainEqual(expect.objectContaining({
      id: "metsaregister-overlay:metsaregister:katsealad",
      type: "raster",
      before: "parcels-selected",
    }));

    const etak = visibilityButtonFor(element, "ETAK - puittaimestik");
    etak.click();
    expect(calls.addSource.some(call => call.id === "forestiq-context-source:MARU_ETAK_WOODY"
      && (call.source as { type?: string }).type === "vector")).toBe(true);

    control.onRemove();
  });

  it("opens parcel detail from the minimal MVT cadastralCode property", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      return Promise.resolve({ ok: true, json: async () => url.includes("/context/layers") ? [] : registry });
    }));
    const selected = vi.fn();
    const { map, listeners } = fakeMap();
    const control = new MetsaregisterOverlayControl("token", selected);
    const element = control.onAdd(map);
    document.body.appendChild(element);
    await settle();

    const forestry = visibilityButtonFor(element, "Kehtivad metsateatised");
    forestry.click();
    listeners.get("click:metsaregister-overlay:metsaregister:teatis:fill")?.({
      features: [{ properties: { cadastralCode: "12345:678:9012" } }],
    });
    expect(selected).toHaveBeenCalledWith("12345:678:9012");
    control.onRemove();
  });

  it("persists visibility and opacity preferences without making mandatory policy user-controlled", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      return Promise.resolve({ ok: true, json: async () => url.includes("/context/layers") ? [] : registry });
    }));
    const first = fakeMap();
    const control = new MetsaregisterOverlayControl();
    const element = control.onAdd(first.map);
    document.body.appendChild(element);
    await settle();

    const forestry = visibilityButtonFor(element, "Kehtivad metsateatised");
    forestry.click();
    const range = [...element.querySelectorAll<HTMLInputElement>('input[type="range"]')][0];
    range.value = "35";
    range.dispatchEvent(new Event("input"));
    control.onRemove();

    const second = fakeMap();
    const restored = new MetsaregisterOverlayControl();
    const restoredElement = restored.onAdd(second.map);
    document.body.appendChild(restoredElement);
    await settle();
    expect(visibilityButtonFor(restoredElement, "Kehtivad metsateatised").getAttribute("aria-pressed")).toBe("true");
    expect(second.calls.addLayer.map(call => call.id)).toContain("metsaregister-overlay:metsaregister:teatis:fill");
    restored.onRemove();
  });


  it("lets parcel detail reveal an approved context layer before descriptors finish loading", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      return Promise.resolve({ ok: true, json: async () => url.includes("/context/layers") ? contextLayers : registry });
    }));
    const { map, calls } = fakeMap();
    const control = new MetsaregisterOverlayControl("token");
    const element = control.onAdd(map);
    document.body.appendChild(element);

    control.setContextLayerVisible("MARU_ETAK_WOODY", true);
    await settle();

    expect(visibilityButtonFor(element, "ETAK - puittaimestik").getAttribute("aria-pressed")).toBe("true");
    expect(calls.addSource.some(call => call.id === "forestiq-context-source:MARU_ETAK_WOODY")).toBe(true);
    expect(window.localStorage.getItem("forestiq.etak-admin-overlays.v1")).toContain('"visible":true');
    control.onRemove();
  });

  it("shows an external-source failure as non-blocking for ForestIQ local data", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      return Promise.resolve({ ok: true, json: async () => url.includes("/context/layers") ? [] : registry });
    }));
    const { map, listeners } = fakeMap();
    const control = new MetsaregisterOverlayControl();
    const element = control.onAdd(map);
    document.body.appendChild(element);
    await settle();

    listeners.get("error")?.({ sourceId: "metsaregister-overlay-source:metsaregister:katsealad" });
    const warning = element.querySelector<HTMLElement>('[role="status"]');
    expect(warning?.hidden).toBe(false);
    expect(warning?.textContent).toContain("ForestIQ kohalikud kihid");
    control.onRemove();
  });
});
