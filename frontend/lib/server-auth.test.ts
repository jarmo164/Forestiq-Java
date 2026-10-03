import { afterEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import {
  accessCookie,
  keycloakInternalUrl,
  keycloakPublicUrl,
  refreshCookie,
  resolveSession,
} from "./server-auth";

function jwt(payload: Record<string, unknown>) {
  const encoded = Buffer.from(JSON.stringify(payload)).toString("base64url");
  return `eyJhbGciOiJub25lIn0.${encoded}.signature`;
}

function requestWithCookies(cookies: Record<string, string>) {
  const cookie = Object.entries(cookies).map(([key, value]) => `${key}=${value}`).join("; ");
  return new NextRequest("http://localhost/auth/session", { headers: cookie ? { cookie } : undefined });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("server-side Keycloak URL resolution", () => {
  it("uses Docker DNS for a localhost public URL while the frontend runs in Compose", () => {
    vi.stubEnv("INTERNAL_API_BASE_URL", "http://api:8080");
    vi.stubEnv("NEXT_PUBLIC_KEYCLOAK_URL", "http://localhost:8180");
    vi.stubEnv("KEYCLOAK_INTERNAL_URL", "");
    vi.stubEnv("KEYCLOAK_INTERNAL_HOSTPORT", "");

    expect(keycloakPublicUrl()).toBe("http://localhost:8180");
    expect(keycloakInternalUrl()).toBe("http://keycloak:8080");
  });

  it("keeps the public URL when Next.js is running directly on the host", () => {
    vi.stubEnv("INTERNAL_API_BASE_URL", "http://localhost:8080");
    vi.stubEnv("NEXT_PUBLIC_KEYCLOAK_URL", "http://localhost:8180/");
    vi.stubEnv("KEYCLOAK_INTERNAL_URL", "");
    vi.stubEnv("KEYCLOAK_INTERNAL_HOSTPORT", "");

    expect(keycloakInternalUrl()).toBe("http://localhost:8180");
  });

  it("always prefers an explicit internal URL", () => {
    vi.stubEnv("INTERNAL_API_BASE_URL", "http://api:8080");
    vi.stubEnv("NEXT_PUBLIC_KEYCLOAK_URL", "https://login.example.test");
    vi.stubEnv("KEYCLOAK_INTERNAL_URL", "http://identity:8080/");

    expect(keycloakInternalUrl()).toBe("http://identity:8080");
    expect(keycloakPublicUrl()).toBe("https://login.example.test");
  });
});

describe("server OIDC session", () => {
  it("returns an anonymous session when no HttpOnly session cookies exist", async () => {
    const session = await resolveSession(requestWithCookies({}));
    expect(session).toMatchObject({ authenticated: false, roles: [] });
    expect(session.accessToken).toBeUndefined();
  });

  it("combines realm and ForestIQ client roles without exposing token parsing to the browser", async () => {
    const access = jwt({
      sub: "caller-a",
      preferred_username: "caller.a",
      exp: Math.floor(Date.now() / 1000) + 600,
      realm_access: { roles: ["CALLER"] },
      resource_access: { "forestiq-web": { roles: ["EVALUATOR", "CALLER"] } },
    });
    const session = await resolveSession(requestWithCookies({ [accessCookie]: access }));
    expect(session.authenticated).toBe(true);
    expect(session.subject).toBe("caller-a");
    expect(session.username).toBe("caller.a");
    expect(session.roles).toEqual(["CALLER", "EVALUATOR"]);
  });

  it("refreshes an expired access token only on the server", async () => {
    const expired = jwt({ sub: "caller-a", exp: Math.floor(Date.now() / 1000) - 30 });
    const refreshed = jwt({ sub: "caller-a", exp: Math.floor(Date.now() / 1000) + 600, realm_access: { roles: ["CALLER"] } });
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      access_token: refreshed,
      refresh_token: "rotated-refresh-token",
      expires_in: 600,
      refresh_expires_in: 3600,
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    const session = await resolveSession(requestWithCookies({
      [accessCookie]: expired,
      [refreshCookie]: "server-only-refresh-token",
    }));

    expect(session.authenticated).toBe(true);
    expect(session.accessToken).toBe(refreshed);
    expect(session.refreshedTokens?.refresh_token).toBe("rotated-refresh-token");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("deduplicates concurrent refreshes for the same rotating refresh token", async () => {
    const expired = jwt({ sub: "caller-a", exp: Math.floor(Date.now() / 1000) - 30 });
    const refreshed = jwt({ sub: "caller-a", exp: Math.floor(Date.now() / 1000) + 600, realm_access: { roles: ["CALLER"] } });
    const fetchMock = vi.fn().mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 25));
      return new Response(JSON.stringify({
        access_token: refreshed,
        refresh_token: "rotated-refresh-token",
        expires_in: 600,
        refresh_expires_in: 3600,
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);

    const request = requestWithCookies({
      [accessCookie]: expired,
      [refreshCookie]: "server-only-refresh-token",
    });
    const sessions = await Promise.all([
      resolveSession(request),
      resolveSession(request),
      resolveSession(request),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(sessions.every((session) => session.authenticated)).toBe(true);
    expect(sessions.map((session) => session.refreshedTokens?.refresh_token)).toEqual([
      "rotated-refresh-token",
      "rotated-refresh-token",
      "rotated-refresh-token",
    ]);
  });
});
