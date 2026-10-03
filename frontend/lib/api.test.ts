import { afterEach, describe, expect, it, vi } from "vitest";
import { apiFetch } from "@/lib/api";

const fetchMock = vi.fn<typeof fetch>();

vi.stubGlobal("fetch", fetchMock);

afterEach(() => {
  fetchMock.mockReset();
});

describe("apiFetch", () => {
  it("returns undefined for successful empty responses", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));

    await expect(apiFetch<void>("/empty")).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith("/api/empty", expect.objectContaining({
      headers: expect.objectContaining({ Accept: "application/json, application/problem+json" }),
    }));
  });

  it("parses successful JSON responses", async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { "content-type": "application/json" },
    }));

    await expect(apiFetch<{ ok: boolean }>("/json")).resolves.toEqual({ ok: true });
  });

  it("uses API error detail when JSON error body is present", async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ detail: "Puudub ligipääs" }), {
      status: 403,
      headers: { "content-type": "application/json", "x-request-id": "req-123" },
    }));

    await expect(apiFetch<void>("/forbidden")).rejects.toThrow("Puudub ligipääs");
  });

  it("parses RFC problem JSON and accepts correlation id from the envelope", async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      code: "AUTHENTICATION_REQUIRED",
      message: "Autentimine on nõutud.",
      correlationId: "problem-401",
    }), {
      status: 401,
      headers: { "content-type": "application/problem+json" },
    }));

    await expect(apiFetch<void>("/protected")).rejects.toThrow("Autentimine on nõutud.");
  });
});
