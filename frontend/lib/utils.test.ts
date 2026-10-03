import { describe, expect, it } from "vitest";
import { cn } from "@/lib/utils";

describe("cn", () => {
  it("merges conflicting Tailwind classes deterministically", () => {
    expect(cn("p-2", "p-4", "text-primary")).toBe("p-4 text-primary");
  });
});
