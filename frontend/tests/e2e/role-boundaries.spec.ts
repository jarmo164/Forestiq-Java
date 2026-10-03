import { expect, test, type Page } from "@playwright/test";
import { e2eUsers, login } from "./helpers/auth";

const ASSIGNED_OWNER_ID = "bbbbbbbb-0000-0000-0000-000000000002";
const OTHER_CALLER_OWNER_ID = "bbbbbbbb-0000-0000-0000-000000000003";
const CROSS_TENANT_OWNER_ID = "99999999-0000-0000-0000-000000000001";

async function api(
  page: Page,
  path: string,
  init?: { method?: string; body?: unknown },
) {
  return page.evaluate(async ({ path, init }) => {
    const response = await fetch(path, {
      method: init?.method ?? "GET",
      credentials: "same-origin",
      headers: init?.body === undefined
        ? { Accept: "application/json, application/problem+json" }
        : {
            Accept: "application/json, application/problem+json",
            "Content-Type": "application/json",
          },
      body: init?.body === undefined ? undefined : JSON.stringify(init.body),
    });
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      body = undefined;
    }
    return { status: response.status, body };
  }, { path, init });
}

test.describe("role and tenant authorization boundaries", () => {
  test("ORG_ADMIN can read its tenant but cannot read a foreign tenant owner", async ({ page }) => {
    await login(page, e2eUsers.admin);

    const allowed = await api(page, `/api/v1/owners/${ASSIGNED_OWNER_ID}`);
    expect(allowed.status).toBe(200);

    const foreignTenant = await api(page, `/api/v1/owners/${CROSS_TENANT_OWNER_ID}`);
    expect(foreignTenant.status).toBe(404);
  });

  test("CRM_MANAGER can read its tenant but cannot read a foreign tenant owner", async ({ page }) => {
    await login(page, e2eUsers.manager);

    const allowed = await api(page, `/api/v1/owners/${ASSIGNED_OWNER_ID}`);
    expect(allowed.status).toBe(200);

    const foreignTenant = await api(page, `/api/v1/owners/${CROSS_TENANT_OWNER_ID}`);
    expect(foreignTenant.status).toBe(404);
  });

  test("EVALUATOR can read organization owners but cannot edit owner contact data", async ({ page }) => {
    await login(page, e2eUsers.evaluator);

    const allowed = await api(page, `/api/v1/owners/${ASSIGNED_OWNER_ID}`);
    expect(allowed.status).toBe(200);

    const denied = await api(page, `/api/v1/owners/${ASSIGNED_OWNER_ID}`, {
      method: "PUT",
      body: { displayName: "E2E Deal Owner" },
    });
    expect(denied.status).toBe(403);
  });

  test("CALLER can read an assigned owner but cannot cross another caller assignment", async ({ page }) => {
    await login(page, e2eUsers.callerA);

    const allowed = await api(page, `/api/v1/owners/${ASSIGNED_OWNER_ID}`);
    expect(allowed.status).toBe(200);

    const denied = await api(page, `/api/v1/owners/${OTHER_CALLER_OWNER_ID}`);
    expect(denied.status).toBe(403);
  });
});
