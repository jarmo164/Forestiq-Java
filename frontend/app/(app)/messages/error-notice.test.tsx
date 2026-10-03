// @vitest-environment jsdom

import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { describe, expect, it, vi } from "vitest";
import { ErrorNotice } from "./error-notice";

describe("ErrorNotice", () => {
  it("renders a failed send message and invokes retry", async () => {
    const onRetry = vi.fn();
    const container = document.createElement("div");
    const root = createRoot(container);
    await act(async () => { root.render(<ErrorNotice message="Sõnumi saatmine ebaõnnestus: server 500" retryLabel="Proovi uuesti" onRetry={onRetry} />); });
    expect(container.querySelector('[role="alert"]')?.textContent).toContain("server 500");
    const button = container.querySelector("button");
    expect(button?.textContent).toContain("Proovi uuesti");
    await act(async () => { button?.click(); });
    expect(onRetry).toHaveBeenCalledOnce();
    await act(async () => root.unmount());
  });
});
