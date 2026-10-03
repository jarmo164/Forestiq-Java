// @vitest-environment jsdom

import { describe, expect, it } from "vitest";
import {
  contextLayerColor,
  rasterLayerId,
  registryLayerColor,
  vectorLayerId,
  vectorLayerIds,
} from "./metsaregister-overlay-map-layers";
import type { ContextLayerDescriptor, MapLayerDescriptor } from "./metsaregister-overlay-types";

const baseLayer: MapLayerDescriptor = {
  key: "metsaregister:teatis",
  title: "Teatis",
  provider: "Metsaregister",
  category: "FORESTIQ_FORESTRY",
  businessMode: "LOCAL_DOMAIN_DATA",
  transport: "MVT",
  minZoom: 9,
  maxZoom: 20,
  defaultOpacity: 0.7,
  order: 1,
  defaultVisible: false,
  queryable: true,
  mandatory: false,
  attribution: "Metsaregister",
  freshnessTarget: "PT6H",
  authorizationScope: "AUTHENTICATED",
  styleKind: "FORESTRY_POLYGON",
  available: true,
};

const contextLayer: ContextLayerDescriptor = {
  sourceKey: "MARU_ADMIN_MUNICIPALITY",
  title: "Omavalitsus",
  datasetFamily: "ADMIN",
  geometryType: "POLYGON",
  minZoom: 7,
  maxZoom: 13,
  defaultOpacity: 0.1,
  caveat: "Ruumiline viitekiht.",
  available: true,
};

describe("metsaregister overlay map layer helpers", () => {
  it("keeps generated layer ids stable", () => {
    expect(rasterLayerId("a:b")).toBe("metsaregister-overlay:a:b");
    expect(vectorLayerId("a:b", "fill")).toBe("metsaregister-overlay:a:b:fill");
    expect(vectorLayerIds("a:b")).toEqual([
      "metsaregister-overlay:a:b:fill",
      "metsaregister-overlay:a:b:line",
      "metsaregister-overlay:a:b:point",
    ]);
  });

  it("resolves stable registry and context colors", () => {
    expect(registryLayerColor(baseLayer)).toBe("#dc2626");
    expect(registryLayerColor({ ...baseLayer, key: "external:wms", transport: "WMS" })).toBe("#64748b");
    expect(contextLayerColor(contextLayer)).toBe("#475569");
    expect(contextLayerColor({ ...contextLayer, datasetFamily: "ETAK" })).toBe("#0f766e");
  });
});
