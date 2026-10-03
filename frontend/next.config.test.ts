import { describe, expect, it } from "vitest";

import nextConfig from "./next.config";

describe("Next.js production security headers", () => {
  it("serves the baseline browser security policy on every route", async () => {
    const rules = await nextConfig.headers?.();
    const rootRule = rules?.find((rule) => rule.source === "/(.*)");
    const headerMap = new Map(rootRule?.headers.map((header) => [header.key, header.value]));

    expect(headerMap.get("Content-Security-Policy")).toContain("default-src 'self'");
    expect(headerMap.get("Content-Security-Policy")).toContain("frame-ancestors 'none'");
    expect(headerMap.get("X-Content-Type-Options")).toBe("nosniff");
    expect(headerMap.get("X-Frame-Options")).toBe("DENY");
    expect(headerMap.get("Referrer-Policy")).toBe("strict-origin-when-cross-origin");
    expect(headerMap.get("Strict-Transport-Security")).toContain("max-age=31536000");
  });
});
