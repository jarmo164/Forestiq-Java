import type { NextRequest, NextResponse } from "next/server";

export const accessCookie = "forestiq.session.access";
export const refreshCookie = "forestiq.session.refresh";
export const verifierCookie = "forestiq.pkce.verifier";
export const stateCookie = "forestiq.oidc.state";
export const returnToCookie = "forestiq.oidc.return-to";

const realm = process.env.NEXT_PUBLIC_KEYCLOAK_REALM ?? "forestiq";
const clientId = process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID ?? "forestiq-web";
const authTimeoutMs = 60_000;
const inFlightRefreshes = new Map<string, Promise<TokenSet>>();

type TokenSet = {
  access_token: string;
  refresh_token?: string;
  expires_in?: number;
  refresh_expires_in?: number;
};

type TokenClaims = {
  sub?: string;
  preferred_username?: string;
  exp?: number;
  realm_access?: { roles?: string[] };
  resource_access?: Record<string, { roles?: string[] }>;
};

export type SessionView = {
  authenticated: boolean;
  username?: string;
  subject?: string;
  roles: string[];
};

export type ResolvedSession = SessionView & {
  accessToken?: string;
  refreshToken?: string;
  refreshedTokens?: TokenSet;
  clearCookies?: boolean;
};

function normalizedUrl(value: string) {
  return value.replace(/\/$/, "");
}

function runningInsideCompose() {
  const internalApi = process.env.INTERNAL_API_BASE_URL?.trim();
  if (!internalApi) return false;
  try {
    return new URL(internalApi).hostname === "api";
  } catch {
    return false;
  }
}

export function keycloakInternalUrl() {
  const explicitUrl = process.env.KEYCLOAK_INTERNAL_URL?.trim();
  if (explicitUrl) return normalizedUrl(explicitUrl);

  const hostPort = process.env.KEYCLOAK_INTERNAL_HOSTPORT?.trim();
  if (hostPort) return `http://${hostPort}`;

  const publicUrl = normalizedUrl(process.env.NEXT_PUBLIC_KEYCLOAK_URL ?? "http://localhost:8180");
  if (runningInsideCompose()) {
    try {
      const hostname = new URL(publicUrl).hostname;
      if (hostname === "localhost" || hostname === "127.0.0.1" || hostname === "::1") {
        return "http://keycloak:8080";
      }
    } catch {
      // Invalid public URLs are handled by fetch/route errors as before.
    }
  }
  return publicUrl;
}

export function keycloakPublicUrl() {
  return normalizedUrl(process.env.NEXT_PUBLIC_KEYCLOAK_URL ?? "http://localhost:8180");
}

export function oidcRealm() {
  return realm;
}

export function oidcClientId() {
  return clientId;
}

export function shouldUseSecureCookies(request?: NextRequest) {
  const proto = request?.headers.get("x-forwarded-proto") ?? request?.nextUrl.protocol.replace(":", "");
  if (proto) return proto === "https";
  return process.env.NODE_ENV === "production";
}

function parseJwt(token?: string): TokenClaims | undefined {
  if (!token) return undefined;
  const [, payload] = token.split(".");
  if (!payload) return undefined;
  try {
    return JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as TokenClaims;
  } catch {
    return undefined;
  }
}

function rolesFrom(claims?: TokenClaims) {
  if (!claims) return [];
  return Array.from(new Set([
    ...(claims.realm_access?.roles ?? []),
    ...(claims.resource_access?.[clientId]?.roles ?? []),
  ])).sort();
}

function cookieOptions(maxAge: number, secure = process.env.NODE_ENV === "production") {
  return {
    httpOnly: true,
    secure,
    sameSite: "lax" as const,
    path: "/",
    maxAge: Math.max(1, maxAge),
  };
}

export function setSessionCookies(response: NextResponse, tokens: TokenSet, secure?: boolean) {
  response.cookies.set(accessCookie, tokens.access_token, cookieOptions(tokens.expires_in ?? 300, secure));
  if (tokens.refresh_token) {
    response.cookies.set(refreshCookie, tokens.refresh_token, cookieOptions(tokens.refresh_expires_in ?? 28_800, secure));
  }
}

export function clearSessionCookies(response: NextResponse) {
  response.cookies.set(accessCookie, "", { ...cookieOptions(1), maxAge: 0 });
  response.cookies.set(refreshCookie, "", { ...cookieOptions(1), maxAge: 0 });
}

async function refreshTokens(refreshToken: string): Promise<TokenSet> {
  const existing = inFlightRefreshes.get(refreshToken);
  if (existing) return existing;

  const refresh = (async () => {
    const response = await fetch(`${keycloakInternalUrl()}/realms/${realm}/protocol/openid-connect/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: clientId,
        refresh_token: refreshToken,
      }),
      signal: AbortSignal.timeout(authTimeoutMs),
      cache: "no-store",
    });
    if (!response.ok) throw new Error(`Keycloak token refresh failed (${response.status})`);
    return response.json() as Promise<TokenSet>;
  })();

  inFlightRefreshes.set(refreshToken, refresh);
  try {
    return await refresh;
  } finally {
    inFlightRefreshes.delete(refreshToken);
  }
}

export async function exchangeAuthorizationCode(code: string, verifier: string, redirectUri: string): Promise<TokenSet> {
  const response = await fetch(`${keycloakInternalUrl()}/realms/${realm}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: clientId,
      redirect_uri: redirectUri,
      code,
      code_verifier: verifier,
    }),
    signal: AbortSignal.timeout(authTimeoutMs),
    cache: "no-store",
  });
  if (!response.ok) throw new Error(`Keycloak token exchange failed (${response.status})`);
  return response.json() as Promise<TokenSet>;
}

export async function revokeRefreshToken(refreshToken?: string) {
  if (!refreshToken) return;
  try {
    await fetch(`${keycloakInternalUrl()}/realms/${realm}/protocol/openid-connect/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ client_id: clientId, refresh_token: refreshToken }),
      signal: AbortSignal.timeout(authTimeoutMs),
      cache: "no-store",
    });
  } catch (error) {
    console.warn("Keycloak logout/revocation unavailable", error);
  }
}

export async function resolveSession(request: NextRequest, refreshBeforeSeconds = 60): Promise<ResolvedSession> {
  let accessToken = request.cookies.get(accessCookie)?.value;
  let refreshToken = request.cookies.get(refreshCookie)?.value;
  let claims = parseJwt(accessToken);
  const expiresSoon = !claims?.exp || claims.exp * 1000 <= Date.now() + refreshBeforeSeconds * 1000;
  let refreshedTokens: TokenSet | undefined;

  if (expiresSoon && refreshToken) {
    try {
      refreshedTokens = await refreshTokens(refreshToken);
      accessToken = refreshedTokens.access_token;
      refreshToken = refreshedTokens.refresh_token ?? refreshToken;
      claims = parseJwt(accessToken);
    } catch (error) {
      console.warn("OIDC session refresh failed", error);
      return { authenticated: false, roles: [], clearCookies: true };
    }
  }

  const active = Boolean(accessToken && claims?.sub && claims?.exp && claims.exp * 1000 > Date.now());
  if (!active) {
    return { authenticated: false, roles: [], clearCookies: Boolean(accessToken || refreshToken) };
  }

  return {
    authenticated: true,
    username: claims?.preferred_username,
    subject: claims?.sub,
    roles: rolesFrom(claims),
    accessToken,
    refreshToken,
    refreshedTokens,
  };
}
