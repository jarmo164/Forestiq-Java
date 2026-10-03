import { createHash, randomBytes } from "crypto";
import { NextRequest, NextResponse } from "next/server";
import { keycloakPublicUrl, oidcClientId, oidcRealm, returnToCookie, shouldUseSecureCookies, stateCookie, verifierCookie } from "@/lib/server-auth";
import { safeInternalPath } from "@/lib/workspace-routes";

function base64Url(input: Buffer) {
  return input.toString("base64url");
}

function authCookieOptions(request: NextRequest) {
  return {
    httpOnly: true,
    secure: shouldUseSecureCookies(request),
    sameSite: "lax" as const,
    maxAge: 300,
    path: "/auth",
  };
}

export function GET(request: NextRequest) {
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? request.nextUrl.host;
  const proto = request.headers.get("x-forwarded-proto") ?? request.nextUrl.protocol.replace(":", "");
  const origin = `${proto}://${host}`;
  const verifier = base64Url(randomBytes(64));
  const challenge = base64Url(createHash("sha256").update(verifier).digest());
  const state = base64Url(randomBytes(32));
  const returnTo = safeInternalPath(request.nextUrl.searchParams.get("returnTo"));
  const params = new URLSearchParams({
    client_id: oidcClientId(),
    redirect_uri: `${origin}/auth/callback`,
    response_type: "code",
    scope: "openid profile email",
    state,
    code_challenge: challenge,
    code_challenge_method: "S256",
  });
  const response = NextResponse.redirect(`${keycloakPublicUrl()}/realms/${oidcRealm()}/protocol/openid-connect/auth?${params}`);
  response.cookies.set(verifierCookie, verifier, authCookieOptions(request));
  response.cookies.set(stateCookie, state, authCookieOptions(request));
  if (returnTo) response.cookies.set(returnToCookie, returnTo, authCookieOptions(request));
  else response.cookies.set(returnToCookie, "", { ...authCookieOptions(request), maxAge: 0 });
  return response;
}
