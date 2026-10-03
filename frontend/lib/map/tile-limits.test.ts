import { describe, expect, it } from "vitest";
import { firstTruncatedTile, visibleTileCoordinates } from "./tile-limits";

describe("visibleTileCoordinates", () => {
  it("returns a bounded set of detail tiles for an Estonian viewport", () => {
    const tiles = visibleTileCoordinates({ west: 24.5, south: 58.1, east: 25.2, north: 58.6 }, 11);
    expect(tiles.length).toBeGreaterThan(0);
    expect(tiles.length).toBeLessThanOrEqual(32);
    expect(tiles.every(tile => tile.z === 11)).toBe(true);
  });

  it("honours the request cap", () => {
    const tiles = visibleTileCoordinates({ west: 20, south: 55, east: 30, north: 60 }, 13, 4);
    expect(tiles).toHaveLength(4);
  });
});

describe("firstTruncatedTile", () => {
  it("returns no warning metadata when all tiles are complete", () => {
    expect(firstTruncatedTile([{ layer: "land_parcel", zoom: 11, featureLimit: 5000, observedFeatures: 120, truncated: false }])).toBeUndefined();
  });

  it("returns the affected layer when a tile was capped", () => {
    expect(firstTruncatedTile([
      { layer: "land_parcel", zoom: 11, featureLimit: 5000, observedFeatures: 5001, truncated: true },
    ])?.layer).toBe("land_parcel");
  });
});
