import { NextRequest, NextResponse } from "next/server";
import { clearSessionCookies, resolveSession, setSessionCookies } from "@/lib/server-auth";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export async function GET(request: NextRequest) {
  const session = await resolveSession(request);
  const response = NextResponse.json(
    {
      authenticated: session.authenticated,
      username: session.username,
      subject: session.subject,
      roles: session.roles,
    },
    { headers: { "Cache-Control": "no-store" } },
  );
  if (session.refreshedTokens) setSessionCookies(response, session.refreshedTokens);
  if (session.clearCookies) clearSessionCookies(response);
  return response;
}
