import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export async function GET() {
  return NextResponse.json(
    {
      releaseId: process.env.FORESTIQ_RELEASE_ID ?? "dev",
      commitSha: process.env.FORESTIQ_COMMIT_SHA ?? "unknown",
    },
    {
      headers: {
        "cache-control": "no-store",
      },
    },
  );
}
