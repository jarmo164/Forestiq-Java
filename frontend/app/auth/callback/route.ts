import { NextRequest, NextResponse } from "next/server";
import { exchangeAuthorizationCode, returnToCookie, setSessionCookies, shouldUseSecureCookies, stateCookie, verifierCookie } from "@/lib/server-auth";
import { safeInternalPath } from "@/lib/workspace-routes";

function publicOrigin(request: NextRequest) {
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? request.nextUrl.host;
  const proto = request.headers.get("x-forwarded-proto") ?? request.nextUrl.protocol.replace(":", "");
  return `${proto}://${host}`;
}

function clearAuthAttemptCookies(response: NextResponse) {
  [verifierCookie, stateCookie, returnToCookie].forEach((name) => {
    response.cookies.set(name, "", { httpOnly: true, sameSite: "lax", path: "/auth", maxAge: 0 });
  });
}

function rejectCallback(origin: string, reason: "state" | "service") {
  const response = NextResponse.redirect(`${origin}/login?error=${reason}`, 303);
  clearAuthAttemptCookies(response);
  return response;
}

export async function GET(request: NextRequest) {
  const code = request.nextUrl.searchParams.get("code");
  const state = request.nextUrl.searchParams.get("state");
  const verifier = request.cookies.get(verifierCookie)?.value;
  const expectedState = request.cookies.get(stateCookie)?.value;
  const origin = publicOrigin(request);
  const returnTo = safeInternalPath(request.cookies.get(returnToCookie)?.value) ?? "/";

  if (!code || !verifier || !state || !expectedState || state !== expectedState) {
    return rejectCallback(origin, "state");
  }

  try {
    const tokens = await exchangeAuthorizationCode(code, verifier, `${origin}/auth/callback`);
    const response = NextResponse.redirect(`${origin}${returnTo}`, 303);
    setSessionCookies(response, tokens, shouldUseSecureCookies(request));
    clearAuthAttemptCookies(response);
    return response;
  } catch (error) {
    console.error("Keycloak token exchange failed", error);
    return rejectCallback(origin, "service");
  }
}
