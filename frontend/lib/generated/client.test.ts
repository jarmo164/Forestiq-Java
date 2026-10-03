import { afterEach, describe, expect, it, vi } from "vitest";

import { generatedApiClient } from "./client";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("generatedApiClient", () => {
  it("uses OpenAPI query types and the existing API proxy transport", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    }), {
      status: 200,
      headers: { "content-type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);

    const response = await generatedApiClient.GET("/api/v1/owners", {
      query: { search: "Mets", page: 0, size: 20 },
    });

    expect(response.content).toEqual([]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/owners?search=Mets&page=0&size=20",
      expect.objectContaining({ method: "GET", credentials: "same-origin" }),
    );
  });

  it("encodes typed OpenAPI path parameters", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "owner/id" }), {
      status: 200,
      headers: { "content-type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);

    await generatedApiClient.GET("/api/v1/owners/{ownerId}", {
      path: { ownerId: "owner/id" },
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/owners/owner%2Fid",
      expect.objectContaining({ method: "GET" }),
    );
  });
});
