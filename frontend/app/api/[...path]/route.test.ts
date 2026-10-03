import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

vi.mock("@/lib/server-auth", () => ({
  clearSessionCookies: vi.fn(),
  resolveSession: vi.fn(),
  setSessionCookies: vi.fn(),
}));

import {
  clearSessionCookies,
  resolveSession,
  setSessionCookies,
} from "@/lib/server-auth";
import { GET, POST } from "./route";

const sessionMock = vi.mocked(resolveSession);
const clearCookiesMock = vi.mocked(clearSessionCookies);
const setCookiesMock = vi.mocked(setSessionCookies);

function context(...path: string[]) {
  return { params: Promise.resolve({ path }) };
}

function request(
  method = "GET",
  options: {
    origin?: string;
    authorization?: string;
    cookie?: string;
    body?: string;
  } = {},
) {
  const headers = new Headers({
    host: "app.example",
    ...(options.origin ? { origin: options.origin } : {}),
    ...(options.authorization ? { authorization: options.authorization } : {}),
    ...(options.cookie ? { cookie: options.cookie } : {}),
  });
  return new NextRequest("https://app.example/api/v1/owners?search=mets", {
    method,
    headers,
    body: options.body,
  });
}

describe("BFF API proxy", () => {
  beforeEach(() => {
    process.env.INTERNAL_API_BASE_URL = "https://api.internal";
    delete process.env.INTERNAL_API_HOSTPORT;
    sessionMock.mockReset();
    clearCookiesMock.mockReset();
    setCookiesMock.mockReset();
    sessionMock.mockResolvedValue({
      authenticated: true,
      roles: [],
      accessToken: "server-session-token",
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    delete process.env.INTERNAL_API_BASE_URL;
    delete process.env.INTERNAL_API_HOSTPORT;
  });

  it("returns 503 before authentication when the upstream address is missing", async () => {
    delete process.env.INTERNAL_API_BASE_URL;
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(request(), context("v1", "owners"));

    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({
      detail: "API teenuse aadress puudub.",
    });
    expect(sessionMock).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("blocks state-changing requests with a foreign Origin", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(
      request("POST", {
        origin: "https://evil.example",
        body: JSON.stringify({ displayName: "Blocked" }),
      }),
      context("v1", "owners"),
    );

    expect(response.status).toBe(403);
    expect(sessionMock).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("returns 401 and clears stale cookies for an unauthenticated session", async () => {
    sessionMock.mockResolvedValue({
      authenticated: false,
      roles: [],
      clearCookies: true,
    });
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(request(), context("v1", "owners"));

    expect(response.status).toBe(401);
    expect(clearCookiesMock).toHaveBeenCalledOnce();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("replaces client auth and strips cookies before forwarding upstream", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      request("GET", {
        authorization: "Bearer attacker-controlled",
        cookie: "private=value",
      }),
      context("v1", "owners"),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("x-forestiq-proxy-attempts")).toBe("1");
    expect(fetchMock).toHaveBeenCalledOnce();

    const [target, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const forwarded = new Headers(init.headers);
    expect(target).toBe("https://api.internal/api/v1/owners?search=mets");
    expect(forwarded.get("authorization")).toBe("Bearer server-session-token");
    expect(forwarded.get("cookie")).toBeNull();
    expect(forwarded.get("host")).toBeNull();
  });

  it("does not retry a failed POST", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response("temporary", { status: 503 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(
      request("POST", {
        origin: "https://app.example",
        body: JSON.stringify({ displayName: "Mari" }),
      }),
      context("v1", "owners"),
    );

    expect(response.status).toBe(503);
    expect(response.headers.get("x-forestiq-proxy-attempts")).toBe("1");
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it("retries an idempotent GET on a transient upstream failure", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("temporary", { status: 503 }))
      .mockResolvedValueOnce(new Response("ok", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const pending = GET(request(), context("v1", "owners"));
    await vi.runAllTimersAsync();
    const response = await pending;

    expect(response.status).toBe(200);
    expect(response.headers.get("x-forestiq-proxy-attempts")).toBe("2");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("writes refreshed session tokens back to the BFF response", async () => {
    sessionMock.mockResolvedValue({
      authenticated: true,
      roles: [],
      accessToken: "refreshed-access",
      refreshedTokens: {
        access_token: "refreshed-access",
        refresh_token: "refreshed-refresh",
      },
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("ok", { status: 200 })),
    );

    const response = await GET(request(), context("v1", "owners"));

    expect(response.status).toBe(200);
    expect(setCookiesMock).toHaveBeenCalledOnce();
  });
});
