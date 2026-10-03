import { describe, expect, it } from "vitest";
import { requireProperties } from "./contract-normalizers";

describe("requireProperties", () => {
  it("returns the original object when required contract fields are present", () => {
    const value = { id: "owner-1", count: 0, active: false };
    const result = requireProperties(value, ["id", "count", "active"] as const, "Owner");

    expect(result).toBe(value);
    expect(result.id).toBe("owner-1");
    expect(result.count).toBe(0);
    expect(result.active).toBe(false);
  });

  it("fails closed when a required field is undefined", () => {
    expect(() =>
      requireProperties({ id: undefined as string | undefined }, ["id"] as const, "Owner"),
    ).toThrow('Owner is missing required field "id".');
  });

  it("fails closed when a required field is null", () => {
    expect(() =>
      requireProperties({ id: null as string | null }, ["id"] as const, "Owner"),
    ).toThrow('Owner is missing required field "id".');
  });
});
