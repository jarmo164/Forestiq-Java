import { describe, expect, it } from "vitest";
import { removeRowAt, restoreRemovedRow, signedExternalUrlError } from "./form-utils";

describe("signedExternalUrlError", () => {
  it("accepts a complete HTTPS URL with a host", () => {
    expect(signedExternalUrlError("https://example.com/contracts/123")).toBeUndefined();
  });

  it.each([
    ["http://example.com/file", "Välisviide peab kasutama HTTPS-i."],
    ["/relative/path", "Sisesta täielik ja kehtiv HTTPS-aadress."],
    ["https://example.com/a b", "Aadress ei tohi sisaldada tühikuid."],
    ["not a url", "Aadress ei tohi sisaldada tühikuid."],
  ])("rejects invalid evidence URL %s", (value, message) => {
    expect(signedExternalUrlError(value)).toBe(message);
  });
});

describe("parcel row undo helpers", () => {
  it("restores the exact removed row at its original position", () => {
    const rows = [{ code: "A", note: "one" }, { code: "B", note: "two" }, { code: "C", note: "three" }];
    const result = removeRowAt(rows, 1);
    expect(result.items.map(row => row.code)).toEqual(["A", "C"]);
    expect(result.removed).toBeDefined();
    expect(restoreRemovedRow(result.items, result.removed!)).toEqual(rows);
  });

  it("does not remove the final mandatory row", () => {
    const rows = [{ code: "A" }];
    expect(removeRowAt(rows, 0)).toEqual({ items: rows });
  });
});
