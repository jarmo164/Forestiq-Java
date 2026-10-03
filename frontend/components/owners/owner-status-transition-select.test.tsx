// @vitest-environment jsdom

import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { describe, expect, it } from "vitest";
import type { OwnerStatusDefinition } from "@/lib/types";
import { OwnerStatusTransitionSelect } from "./owner-status-transition-select";

const allowedFromServer: OwnerStatusDefinition[] = [
  { id: "CONTACTED", colorHex: "cccccc", durationDays: 0, isProtected: false },
];

describe("OwnerStatusTransitionSelect", () => {
  it("renders an allowed transition and does not invent a forbidden workflow transition", async () => {
    const container = document.createElement("div");
    const root = createRoot(container);
    await act(async () => { root.render(<OwnerStatusTransitionSelect definitions={allowedFromServer} value="" onChange={() => undefined} />); });
    expect(container.querySelector('option[value="CONTACTED"]')?.textContent).toBe("Kontakteeritud");
    expect(container.querySelector('option[value="EVALUATION"]')).toBeNull();
    expect(container.querySelector('option[value="WAITS_FOR_EVALUATION"]')).toBeNull();
    await act(async () => root.unmount());
  });
});
