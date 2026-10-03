// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Map as MapLibreMap } from "maplibre-gl";
import { MaaruumBasemapControl } from "./maaruum-basemap-control";

const catalog = {
  version: "2026-09-07",
  layers: [
    {
      key: "kaart",
      title: "Eesti kaart",
      minZoom: 0,
      maxZoom: 19,
      attribution: "Maa- ja Ruumiamet",
      sourceDate: "2026-09-01",
      freshness: "PROVIDER_MANAGED",
      tileUrlTemplate: "/api/v1/map/basemaps/kaart/{z}/{x}/{y}",
      defaultSelected: true,
    },
    {
      key: "ortofoto",
      title: "Ortofoto",
      minZoom: 0,
      maxZoom: 19,
      attribution: "Maa- ja Ruumiamet",
      sourceDate: "2026",
      freshness: "PROVIDER_MANAGED",
      tileUrlTemplate: "/api/v1/map/basemaps/ortofoto/{z}/{x}/{y}",
      defaultSelected: false,
    },
  ],
};

beforeEach(() => window.localStorage.clear());
afterEach(() => {
  vi.unstubAllGlobals();
  document.body.replaceChildren();
});

function fakeMap() {
  const sources = new Set<string>();
  const layers = new Set<string>(["parcels-overview", "parcels-fill"]);
  let errorHandler: ((event: unknown) => void) | undefined;
  const calls = {
    addSource: [] as Array<{ id: string; value: unknown }>,
    layout: [] as Array<{ id: string; property: string; value: unknown }>,
  };
  const map = {
    on: vi.fn((event: string, handler: (value: unknown) => void) => { if (event === "error") errorHandler = handler; return map; }),
    off: vi.fn(() => map),
    once: vi.fn((_event: string, handler: () => void) => { handler(); return map; }),
    isStyleLoaded: vi.fn(() => true),
    getSource: vi.fn((id: string) => sources.has(id) ? ({}) : undefined),
    addSource: vi.fn((id: string, value: unknown) => { sources.add(id); calls.addSource.push({ id, value }); return map; }),
    getLayer: vi.fn((id: string) => layers.has(id) ? ({ id }) : undefined),
    addLayer: vi.fn((layer: { id: string }) => { layers.add(layer.id); return map; }),
    setLayoutProperty: vi.fn((id: string, property: string, value: unknown) => { calls.layout.push({ id, property, value }); return map; }),
  };
  return { map: map as unknown as MapLibreMap, calls, emitError: (sourceId: string) => errorHandler?.({ sourceId }) };
}

async function settle() {
  await new Promise(resolve => setTimeout(resolve, 0));
}

describe("MaaruumBasemapControl", () => {
  it("uses only ForestIQ proxy descriptors and persists basemap changes", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => catalog }));
    const fake = fakeMap();
    const control = new MaaruumBasemapControl();
    const element = control.onAdd(fake.map);
    document.body.appendChild(element);
    await settle();

    const select = element.querySelector("select") as HTMLSelectElement;
    expect(select.value).toBe("kaart");
    expect(fake.calls.addSource).toHaveLength(2);
    for (const source of fake.calls.addSource) {
      expect(JSON.stringify(source.value)).toContain("/api/v1/map/basemaps/");
      expect(JSON.stringify(source.value)).not.toContain("tiles.maaamet.ee");
    }

    select.value = "ortofoto";
    select.dispatchEvent(new Event("change"));
    expect(window.localStorage.getItem("forestiq.basemap.v1")).toBe("ortofoto");
    expect(fake.calls.layout.some(call => call.id.endsWith("ortofoto") && call.value === "visible")).toBe(true);
    expect(element.textContent).toContain("andmeseis 2026");
    control.onRemove();
  });

  it("restores preference and shows non-blocking provider failure warning", async () => {
    window.localStorage.setItem("forestiq.basemap.v1", "ortofoto");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => catalog }));
    const fake = fakeMap();
    const control = new MaaruumBasemapControl();
    const element = control.onAdd(fake.map);
    document.body.appendChild(element);
    await settle();

    expect((element.querySelector("select") as HTMLSelectElement).value).toBe("ortofoto");
    fake.emitError("forestiq-basemap-source-ortofoto");
    expect(element.textContent).toContain("ForestIQ kinnistud ja detailandmed jäävad kasutatavaks");
    control.onRemove();
  });

  it("keeps map usable when the catalog itself is unavailable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 502 }));
    const fake = fakeMap();
    const control = new MaaruumBasemapControl();
    const element = control.onAdd(fake.map);
    document.body.appendChild(element);
    await settle();

    expect(element.textContent).toContain("ForestIQ andmekihid jäävad kasutatavaks");
    expect(fake.calls.addSource).toHaveLength(0);
    control.onRemove();
  });
});
