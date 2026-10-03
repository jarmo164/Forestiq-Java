import { describe, expect, it } from "vitest";
import {
  defaultWorkspacePath,
  isWorkspacePathAllowed,
  rolesForWorkspacePath,
  safeInternalPath,
} from "./workspace-routes";

describe("workspace route policy", () => {
  it("keeps role defaults aligned with the delivered workspaces", () => {
    expect(defaultWorkspacePath(["CALLER"])).toBe("/sales");
    expect(defaultWorkspacePath(["EVALUATOR"])).toBe("/evaluation-workdesk");
    expect(defaultWorkspacePath(["CRM_MANAGER"])).toBe("/overview");
    expect(defaultWorkspacePath(["ORG_ADMIN"])).toBe("/overview");
    expect(defaultWorkspacePath(["VIEWER"])).toBe("/map");
  });

  it("uses the same route policy for direct URLs and navigation visibility", () => {
    expect(rolesForWorkspacePath("/admin/users")).toEqual(["ORG_ADMIN", "CRM_MANAGER"]);
    expect(isWorkspacePathAllowed("/admin/users", ["CALLER"])).toBe(false);
    expect(isWorkspacePathAllowed("/admin/users", ["CRM_MANAGER"])).toBe(true);

    expect(isWorkspacePathAllowed("/evaluation-workdesk/case-1", ["CALLER"])).toBe(false);
    expect(isWorkspacePathAllowed("/evaluation-workdesk/case-1", ["EVALUATOR"])).toBe(true);

    expect(isWorkspacePathAllowed("/map", ["VIEWER"])).toBe(true);
  });

  it("restores only safe internal deep links", () => {
    expect(safeInternalPath("/owners/123?tab=history#latest")).toBe("/owners/123?tab=history#latest");
    expect(safeInternalPath("//evil.example.test/path")).toBeUndefined();
    expect(safeInternalPath("https://evil.example.test/path")).toBeUndefined();
    expect(safeInternalPath("/\\evil.example.test")).toBeUndefined();
  });

  it("does not accidentally expose admin contract routes to CRM managers", () => {
    expect(isWorkspacePathAllowed("/admin/contracts", ["CRM_MANAGER"])).toBe(false);
    expect(isWorkspacePathAllowed("/admin/contracts", ["ORG_ADMIN"])).toBe(true);
  });
});
