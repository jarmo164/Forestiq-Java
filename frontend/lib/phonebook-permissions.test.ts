import { describe, expect, it } from "vitest";
import { canDeletePhonebookEntry } from "@/lib/phonebook-permissions";

describe("canDeletePhonebookEntry", () => {
  it("allows organization admins and CRM managers", () => {
    expect(canDeletePhonebookEntry(["ORG_ADMIN"])).toBe(true);
    expect(canDeletePhonebookEntry(["CRM_MANAGER"])).toBe(true);
  });

  it("does not allow caller or viewer roles", () => {
    expect(canDeletePhonebookEntry(["CALLER"])).toBe(false);
    expect(canDeletePhonebookEntry(["VIEWER"])).toBe(false);
  });
});
