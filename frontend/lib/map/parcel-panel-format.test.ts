import { describe, expect, it } from "vitest";
import {
  displayValue,
  formatArea,
  formatIsoDate,
  formatNumber,
  formatPercent,
  formatRegistryDate,
  freshnessLabel,
  statusLabel,
} from "./parcel-panel-format";

describe("parcel panel formatting", () => {
  it("formats nullable numeric values for Estonian parcel UI", () => {
    expect(formatArea(null)).toBe("—");
    expect(formatArea(12.345)).toBe("12,35 ha");
    expect(formatNumber(7.123, " tk")).toBe("7,12 tk");
    expect(formatPercent(42.55)).toBe("42,6%");
  });

  it("formats registry and ISO dates defensively", () => {
    expect(formatRegistryDate(20260917)).toBe("17.9.2026");
    expect(formatRegistryDate(undefined)).toBe("—");
    expect(formatIsoDate(null)).toBe("teadmata");
    expect(formatIsoDate("not-a-date")).toBe("not-a-date");
  });

  it("maps statuses and freshness labels with safe fallbacks", () => {
    expect(displayValue("  Metsaomanik  ")).toBe("Metsaomanik");
    expect(displayValue("")).toBe("—");
    expect(statusLabel("INTERESTED")).toBe("Huvitatud");
    expect(statusLabel(null)).toBe("CRM omanik puudub");
    expect(freshnessLabel("FAILED_REFRESH")).toBe("viimane uuendus ebaõnnestus");
    expect(freshnessLabel("CUSTOM_STATE")).toBe("custom state");
  });
});
