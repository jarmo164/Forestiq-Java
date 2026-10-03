// @vitest-environment jsdom

import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { describe, expect, it, vi } from "vitest";
import { PhonebookDeleteAction, PhonebookErrorNotice } from "./phonebook-feedback";

describe("phonebook feedback controls", () => {
  it("hides delete from CALLER and shows it to ORG_ADMIN", async () => {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => { root.render(<PhonebookDeleteAction roles={["CALLER"]} entryId={1} entryName="Test" pending={false} onConfirm={() => undefined} />); });
    expect(container.querySelector('button[aria-label="Kustuta kontakt Test"]')).toBeNull();
    await act(async () => { root.render(<PhonebookDeleteAction roles={["ORG_ADMIN"]} entryId={1} entryName="Test" pending={false} onConfirm={() => undefined} />); });
    expect(container.querySelector('button[aria-label="Kustuta kontakt Test"]')).not.toBeNull();
    await act(async () => root.unmount());
    container.remove();
  });

  it("renders API failure and lets the user retry", async () => {
    const retry = vi.fn();
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => { root.render(<PhonebookErrorNotice message="Kontaktide laadimine ebaõnnestus: server 500" onRetry={retry} />); });
    expect(container.querySelector('[role="alert"]')?.textContent).toContain("server 500");
    const button = container.querySelector("button");
    await act(async () => button?.click());
    expect(retry).toHaveBeenCalledOnce();
    await act(async () => root.unmount());
    container.remove();
  });
});
