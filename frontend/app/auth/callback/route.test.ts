import { describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET } from "./route";
import { stateCookie, verifierCookie } from "@/lib/server-auth";

describe("OIDC callback", () => {
  it("redirects a mismatched state to a recoverable login error before token exchange", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const request = new NextRequest("http://localhost/auth/callback?code=abc&state=wrong", {
      headers: { cookie: `${verifierCookie}=verifier; ${stateCookie}=expected` },
    });

    const response = await GET(request);

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("http://localhost/login?error=state");
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
