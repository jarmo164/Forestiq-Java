// @vitest-environment jsdom

import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, it } from "vitest";
import type { CompleteMapParcelSummary } from "@/lib/api/map-cadastre";
import { ParcelOverview } from "./map-parcel-panel";

function parcel(overrides: Partial<CompleteMapParcelSummary> = {}): CompleteMapParcelSummary {
  return {
    cadastralCode: "12345:001:0001",
    marked: false,
    owners: [],
    ...overrides,
  } as CompleteMapParcelSummary;
}

async function renderOverview(value: CompleteMapParcelSummary, ownerCount = 0, labels: string[] = []) {
  const container = document.createElement("div");
  const root = createRoot(container);
  await act(async () => {
    root.render(<ParcelOverview parcel={value} ownerCount={ownerCount} labels={labels} />);
  });
  return { container, root };
}

afterEach(() => {
  document.body.replaceChildren();
});

describe("ParcelOverview", () => {
  it("renders complete decision data and source metadata", async () => {
    const { container, root } = await renderOverview(parcel({
      areaHectares: 12.5,
      forestArea: 8.25,
      ownerCount: 2,
      subPartCount: 4,
      activeNotificationCount: 1,
      forestFeatureCount: 9,
      address: "Test tee 1",
      municipality: "Tartu",
      county: "Tartumaa",
      sourceDate: "2026-09-15",
      generationId: "12345678-1111-2222-3333-444444444444",
      ownerStatus: "CONTACTED",
      marked: true,
    }), 2, ["NATURA_2000"]);
    const text = container.textContent ?? "";
    expect(text).toContain("12,5 ha");
    expect(text).toContain("8,25 ha");
    expect(text).toContain("Natura 2000");
    expect(text).toContain("andmeseis");
    expect(text).toContain("gen 12345678");
    await act(async () => root.unmount());
  });

  it("distinguishes missing values from measured zero", async () => {
    const { container, root } = await renderOverview(parcel({
      areaHectares: undefined,
      forestArea: 0,
      ownerCount: null,
      subPartCount: null,
      activeNotificationCount: null,
      forestFeatureCount: 0,
    }), 0);
    const text = container.textContent ?? "";
    expect(text).toContain("—");
    expect(text).toContain("0 ha");
    expect(text).toContain("Asukoht teadmata");
    await act(async () => root.unmount());
  });

  it("keeps large detail counts summarized instead of rendering detail rows", async () => {
    const { container, root } = await renderOverview(parcel({
      ownerCount: 125_000,
      subPartCount: 80_000,
      activeNotificationCount: 40_000,
      forestFeatureCount: 250_000,
    }), 125_000);
    const text = container.textContent ?? "";
    expect(text).toContain("125 000");
    expect(text).toContain("80 000");
    expect(text).toContain("40 000");
    expect(text).toContain("250 000");
    expect(container.querySelectorAll("li")).toHaveLength(0);
    await act(async () => root.unmount());
  });
});
