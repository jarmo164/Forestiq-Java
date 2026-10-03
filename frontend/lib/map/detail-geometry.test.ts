import { describe, expect, it } from "vitest";
import { parseDetailGeometry } from "./detail-geometry";

describe("parseDetailGeometry", () => {
  it("parses GeoJSON Polygon and Feature payloads", () => {
    const polygon = parseDetailGeometry(JSON.stringify({
      type: "Polygon",
      coordinates: [[[24, 59], [25, 59], [25, 60], [24, 59]]],
    }));
    expect(polygon?.type).toBe("Polygon");

    const feature = parseDetailGeometry(JSON.stringify({
      type: "Feature",
      properties: {},
      geometry: { type: "Polygon", coordinates: [[[24, 59], [25, 59], [25, 60], [24, 59]]] },
    }));
    expect(feature?.type).toBe("Polygon");
  });

  it("parses WKT Polygon and MultiPolygon", () => {
    const polygon = parseDetailGeometry("POLYGON((24 59,25 59,25 60,24 59))");
    expect(polygon).toEqual({
      type: "Polygon",
      coordinates: [[[24, 59], [25, 59], [25, 60], [24, 59]]],
    });

    const multi = parseDetailGeometry("MULTIPOLYGON(((24 59,25 59,25 60,24 59)),((26 59,27 59,27 60,26 59)))");
    expect(multi?.type).toBe("MultiPolygon");
    expect(multi?.coordinates).toHaveLength(2);
  });

  it("rejects invalid or non-polygon geometry", () => {
    expect(parseDetailGeometry("LINESTRING(24 59,25 60)")).toBeNull();
    expect(parseDetailGeometry('{"type":"Polygon","coordinates":[]}')).toBeNull();
    expect(parseDetailGeometry("not geometry")).toBeNull();
  });
});
