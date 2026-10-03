// @vitest-environment jsdom

import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DataSection, QueryMessage, SourceMeta } from "./map-parcel-panel-shared";

async function render(node: React.ReactNode) {
  const container = document.createElement("div");
  const root = createRoot(container);
  await act(async () => {
    root.render(node);
  });
  return { container, root };
}

afterEach(() => {
  document.body.replaceChildren();
});

describe("parcel detail shared contract", () => {
  it("renders provenance, generation and freshness together", async () => {
    const { container, root } = await render(
      <SourceMeta
        source="Metsaregister"
        sourceDate="2026-09-15"
        generationId="12345678-1111-2222-3333-444444444444"
        freshness="STALE"
      />,
    );

    const text = container.textContent ?? "";
    expect(text).toContain("Metsaregister");
    expect(text).toContain("andmeseis");
    expect(text).toContain("gen 12345678");
    expect(text).toContain("aegunud");

    await act(async () => root.unmount());
  });

  it("keeps a section failure local and offers retry", async () => {
    const retry = vi.fn();
    const { container, root } = await render(
      <QueryMessage
        loading={false}
        error
        empty={false}
        emptyText="Andmeid pole."
        retry={retry}
      />,
    );

    expect(container.getAttribute("role")).toBeNull();
    expect(container.textContent).toContain("Ülejäänud katastriinfo töötab edasi.");

    const button = container.querySelector("button");
    expect(button?.textContent).toContain("Proovi uuesti");
    await act(async () => button?.click());
    expect(retry).toHaveBeenCalledTimes(1);

    await act(async () => root.unmount());
  });

  it("keeps detail content progressively disclosed by default", async () => {
    const { container, root } = await render(
      <DataSection title="Metsateatised" count={42}>
        <div data-testid="detail">detail</div>
      </DataSection>,
    );

    const details = container.querySelector("details");
    expect(details?.open).toBe(false);
    expect(container.textContent).toContain("Metsateatised");
    expect(container.textContent).toContain("42");

    await act(async () => root.unmount());
  });
});
