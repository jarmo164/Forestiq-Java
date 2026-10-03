"use client";

import { useAuth } from "@/lib/auth";

export const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? "/api";

type ApiProblem = {
  detail?: string;
  message?: string;
  correlationId?: string;
  code?: string;
  errors?: Array<{ field?: string; message?: string }>;
};

export class ApiError extends Error {
  readonly status: number;
  readonly correlationId?: string;
  readonly code?: string;
  readonly fieldErrors: Array<{ field?: string; message?: string }>;
  readonly retryAfter: string | null;

  constructor(
    message: string,
    options: {
      status: number;
      correlationId?: string;
      code?: string;
      fieldErrors?: Array<{ field?: string; message?: string }>;
      retryAfter?: string | null;
    },
  ) {
    super(message);
    this.name = "ApiError";
    this.status = options.status;
    this.correlationId = options.correlationId;
    this.code = options.code;
    this.fieldErrors = options.fieldErrors ?? [];
    this.retryAfter = options.retryAfter ?? null;
  }
}

function isJsonContentType(contentType: string): boolean {
  const mediaType = contentType.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  return mediaType === "application/json" || mediaType.endsWith("+json");
}

async function apiError(response: Response): Promise<ApiError> {
  const headerRequestId = response.headers.get("x-request-id");
  const contentType = response.headers.get("content-type") ?? "";
  let body: ApiProblem | undefined;
  if (isJsonContentType(contentType)) {
    try {
      body = await response.json() as ApiProblem;
    } catch {
      body = undefined;
    }
  }
  const requestId = headerRequestId ?? body?.correlationId;
  const fallbackMessage = response.status === 429
    ? "API päringuid on hetkel liiga palju. Palun proovi hetke pärast uuesti."
    : `API päring ebaõnnestus (${response.status})`;
  const detail = body?.detail ?? body?.message ?? fallbackMessage;
  const message = requestId ? `${detail} Päringu ID: ${requestId}` : detail;
  return new ApiError(message, {
    status: response.status,
    correlationId: requestId,
    code: body?.code,
    fieldErrors: body?.errors,
    retryAfter: response.headers.get("retry-after"),
  });
}

async function apiResponse<T>(response: Response): Promise<T> {
  if (!response.ok) throw await apiError(response);
  if (response.status === 204 || response.status === 205) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  const contentType = response.headers.get("content-type") ?? "";
  return (isJsonContentType(contentType) ? JSON.parse(text) : text) as T;
}

export async function apiFetch<T>(path: string, _accessToken?: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...init,
    credentials: "same-origin",
    headers: {
      Accept: "application/json, application/problem+json",
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });
  return apiResponse<T>(response);
}

export async function apiMultipartFetch<T>(path: string, _accessToken: string | undefined, body: FormData): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: "POST",
    credentials: "same-origin",
    headers: { Accept: "application/json, application/problem+json" },
    body,
  });
  return apiResponse<T>(response);
}

export function useToken() {
  const { authenticated } = useAuth();
  return { accessToken: undefined, enabled: authenticated };
}
