import { describe, expect, it } from "vitest";

import { apiTransport } from "@/lib/api/transport";
import { generatedApiClient } from "@/lib/generated/client";

describe("apiTransport", () => {
  it("delegates typed JSON calls to the generated OpenAPI client", () => {
    expect(apiTransport).toBe(generatedApiClient);
    expect(apiTransport.GET).toBeTypeOf("function");
    expect(apiTransport.POST).toBeTypeOf("function");
    expect(apiTransport.PUT).toBeTypeOf("function");
    expect(apiTransport.PATCH).toBeTypeOf("function");
    expect(apiTransport.DELETE).toBeTypeOf("function");
  });
});
