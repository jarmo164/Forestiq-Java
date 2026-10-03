import { describe, expect, it } from "vitest";
import type { CompleteMapParcelSummary } from "@/lib/api/map-cadastre";
import { formatCount, landUseSegments, percentOf, positiveArea } from "./map-parcel-panel-utils";

function parcel(overrides: Partial<CompleteMapParcelSummary>): CompleteMapParcelSummary {
  return { cadastralCode: "12345:001:0001", marked: false, owners: [], ...overrides } as CompleteMapParcelSummary;
}

describe("map parcel panel utils", () => {
  it("keeps missing, invalid and zero area semantics distinct", () => {
    expect(positiveArea(undefined)).toBe(0);
    expect(positiveArea(-1)).toBe(0);
    expect(positiveArea(0)).toBe(0);
    expect(positiveArea(1.25)).toBe(1.25);
  });

  it("bounds percentages and rejects invalid totals", () => {
    expect(percentOf(2, 4)).toBe(50);
    expect(percentOf(6, 4)).toBe(100);
    expect(percentOf(1, 0)).toBeNull();
  });

  it("builds land-use segments with unknown remainder", () => {
    const segments = landUseSegments(parcel({ areaHectares: 10, forestArea: 4, arableArea: 2 }));
    expect(segments.map(segment => segment.label)).toEqual(["Metsamaa", "Haritav maa", "Tuvastamata"]);
    expect(segments.at(-1)?.value).toBe(4);
  });

  it("formats missing and large counts for compact overview display", () => {
    expect(formatCount(null)).toBe("—");
    expect(formatCount(125000)).toBe("125 000");
  });
});
