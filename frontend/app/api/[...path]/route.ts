import { NextRequest, NextResponse } from "next/server";
import { clearSessionCookies, resolveSession, setSessionCookies } from "@/lib/server-auth";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

const retryableStatuses = new Set([502, 503, 504]);
const retryDelaysMs = [0, 2_500, 5_000, 10_000, 15_000];
const upstreamTimeoutMs = 75_000;
const cadastreCsvUploadTimeoutMs = 5_400_000;

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function apiBaseUrl() {
  const privateHostPort = process.env.INTERNAL_API_HOSTPORT?.trim();
  if (privateHostPort) {
    const value = /^https?:\/\//i.test(privateHostPort) ? privateHostPort : `http://${privateHostPort}`;
    return value.replace(/\/$/, "");
  }
  const value = process.env.INTERNAL_API_BASE_URL?.trim();
  return value ? value.replace(/\/$/, "") : undefined;
}

function publicOrigin(request: NextRequest) {
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? request.nextUrl.host;
  const proto = request.headers.get("x-forwarded-proto") ?? request.nextUrl.protocol.replace(":", "");
  return `${proto}://${host}`;
}

function csrfAllowed(request: NextRequest) {
  const method = request.method.toUpperCase();
  if (method === "GET" || method === "HEAD" || method === "OPTIONS") return true;
  return request.headers.get("origin") === publicOrigin(request);
}

function requestHeaders(request: NextRequest, accessToken: string) {
  const headers = new Headers(request.headers);
  headers.delete("host");
  headers.delete("connection");
  headers.delete("content-length");
  headers.delete("accept-encoding");
  headers.delete("cookie");
  headers.delete("authorization");
  headers.set("Authorization", `Bearer ${accessToken}`);
  return headers;
}

function responseHeaders(response: Response, attempts: number) {
  const headers = new Headers(response.headers);
  headers.delete("connection");
  headers.delete("content-length");
  headers.delete("content-encoding");
  headers.delete("transfer-encoding");
  headers.set("x-forestiq-proxy-attempts", String(attempts));
  return headers;
}

function isCadastreCsvUpload(method: string, path: string[]) {
  return method === "POST" && path.join("/") === "v1/admin/imports/cadastre";
}

type StreamingRequestInit = RequestInit & { duplex?: "half" };

async function proxy(request: NextRequest, path: string[]) {
  const baseUrl = apiBaseUrl();
  if (!baseUrl) {
    console.error("ForestIQ API proxy is missing INTERNAL_API_HOSTPORT/INTERNAL_API_BASE_URL");
    return NextResponse.json({ detail: "API teenuse aadress puudub." }, { status: 503 });
  }
  if (!csrfAllowed(request)) {
    return NextResponse.json({ detail: "Päringu päritolu ei ole lubatud." }, { status: 403 });
  }

  const session = await resolveSession(request, 5);
  if (!session.authenticated || !session.accessToken) {
    const response = NextResponse.json({ detail: "Autentimine on vajalik." }, { status: 401 });
    if (session.clearCookies) clearSessionCookies(response);
    return response;
  }

  const suffix = path.map(encodeURIComponent).join("/");
  const target = `${baseUrl}/api/${suffix}${request.nextUrl.search}`;
  const method = request.method.toUpperCase();
  const streamingCadastreUpload = isCadastreCsvUpload(method, path);
  const canRetry = method === "GET" || method === "HEAD" || method === "OPTIONS";
  const body = method === "GET" || method === "HEAD"
    ? undefined
    : streamingCadastreUpload
      ? request.body
      : await request.arrayBuffer();
  const attempts = canRetry ? retryDelaysMs.length : 1;
  const timeoutMs = streamingCadastreUpload ? cadastreCsvUploadTimeoutMs : upstreamTimeoutMs;
  let lastError: unknown;

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const delay = retryDelaysMs[attempt] ?? 0;
    if (delay > 0) await sleep(delay);

    try {
      const init: StreamingRequestInit = {
        method,
        headers: requestHeaders(request, session.accessToken),
        body,
        redirect: "manual",
        cache: "no-store",
        signal: AbortSignal.timeout(timeoutMs),
      };
      if (streamingCadastreUpload && body) init.duplex = "half";

      const upstream = await fetch(target, init);

      if (upstream.status === 429) {
        const retryAfter = upstream.headers.get("retry-after") ?? "not-provided";
        console.warn(
          "ForestIQ API rate limited",
          `/api/${suffix}`,
          `retry-after=${retryAfter}`,
          `attempt ${attempt + 1}/${attempts}`,
        );
      }

      if (canRetry && retryableStatuses.has(upstream.status) && attempt + 1 < attempts) {
        console.warn("ForestIQ API temporarily unavailable", upstream.status, `/api/${suffix}`, `attempt ${attempt + 1}/${attempts}`);
        await upstream.arrayBuffer().catch(() => undefined);
        continue;
      }

      const response = new NextResponse(upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers: responseHeaders(upstream, attempt + 1),
      });
      if (session.refreshedTokens) setSessionCookies(response, session.refreshedTokens);
      return response;
    } catch (error) {
      lastError = error;
      console.error("ForestIQ API proxy request failed", `/api/${suffix}`, `attempt ${attempt + 1}/${attempts}`, error);
      if (!canRetry || attempt + 1 >= attempts) break;
    }
  }

  console.error("ForestIQ API unavailable after retries", `/api/${suffix}`, lastError);
  const response = NextResponse.json(
    { detail: "API teenus käivitub või ei ole hetkel kättesaadav. Palun proovi hetke pärast uuesti." },
    { status: 502, headers: { "x-forestiq-proxy-attempts": String(attempts) } },
  );
  if (session.refreshedTokens) setSessionCookies(response, session.refreshedTokens);
  return response;
}

type RouteContext = { params: Promise<{ path: string[] }> };

async function handle(request: NextRequest, context: RouteContext) {
  const { path } = await context.params;
  return proxy(request, path);
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
export const OPTIONS = handle;
export const HEAD = handle;
