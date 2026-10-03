import { NextRequest, NextResponse } from "next/server";
import { clearSessionCookies, refreshCookie, revokeRefreshToken } from "@/lib/server-auth";

export const runtime = "nodejs";

export async function GET(request: NextRequest) {
  await revokeRefreshToken(request.cookies.get(refreshCookie)?.value);
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? request.nextUrl.host;
  const proto = request.headers.get("x-forwarded-proto") ?? request.nextUrl.protocol.replace(":", "");
  const response = NextResponse.redirect(`${proto}://${host}/login`);
  clearSessionCookies(response);
  return response;
}
