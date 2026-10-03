import { expect, test } from "@playwright/test";
import { e2eUsers, login } from "./helpers/auth";

const ASSIGNMENT_OWNER_ID = "bbbbbbbb-0000-0000-0000-000000000001";
const ASSIGNMENT_OWNER = "E2E Assignment Owner";
const DEAL_OWNER = "E2E Deal Owner";
const SHARED_CADASTRAL_CODE = "10001:001:0002";

async function filterRelations(page: import("@playwright/test").Page, query: string) {
  const response = page.waitForResponse(item =>
    item.url().includes("/api/v1/ownership-relations") && item.request().method() === "GET",
  );
  await page.getByPlaceholder("Omanik, isiku-/registrikood või katastritunnus").fill(query);
  expect((await response).ok()).toBeTruthy();
}

test.describe.serial("owner-cadastre relationship management", () => {
  test("manager can add, find, audit and end a temporary co-owner relation", async ({ page }) => {
    await login(page, e2eUsers.manager);
    await page.goto("/admin");
    await expect(page.getByRole("heading", { name: "Haldus" })).toBeVisible();
    await page.getByRole("button", { name: "Omaniku–katastri seosed" }).click();
    await expect(page.getByText("Omaniku–katastri seosed", { exact: true }).first()).toBeVisible();

    await page.getByPlaceholder("Otsi omanikku nime või registrikoodi järgi").fill(ASSIGNMENT_OWNER);
    await page.getByRole("button", { name: new RegExp(ASSIGNMENT_OWNER) }).click();
    await page.getByPlaceholder("Katastritunnus 12345:678:9012").fill(SHARED_CADASTRAL_CODE);
    await page.getByPlaceholder("Muudatuse põhjus (kohustuslik)").fill("E2E temporary co-owner verification");

    const createResponsePromise = page.waitForResponse(response =>
      response.url().endsWith("/api/v1/ownership-relations") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Lisa seos", exact: true }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const created = await createResponse.json() as { source: string; manualOverride: boolean; parcelId: string };
    expect(created.source).toBe("MANUAL");
    expect(created.manualOverride).toBeTruthy();
    expect(created.parcelId).toBeTruthy();

    await filterRelations(page, SHARED_CADASTRAL_CODE);
    const assignmentRow = page.getByRole("row", { name: new RegExp(`${ASSIGNMENT_OWNER}.*${SHARED_CADASTRAL_CODE}`) });
    const existingOwnerRow = page.getByRole("row", { name: new RegExp(`${DEAL_OWNER}.*${SHARED_CADASTRAL_CODE}`) });
    await expect(assignmentRow).toContainText(SHARED_CADASTRAL_CODE);
    await expect(assignmentRow).toContainText("Aktiivne");
    await expect(assignmentRow).toContainText("Käsitsi kaitstud");
    await expect(existingOwnerRow).toContainText("Aktiivne");
    await expect(page.getByText(/2 aktiivset omanikku/).first()).toBeVisible();

    await assignmentRow.getByRole("button", { name: "Ajalugu" }).click();
    const historyCard = page.getByRole("heading", {
      name: `Seose ajalugu · ${ASSIGNMENT_OWNER} ↔ ${SHARED_CADASTRAL_CODE}`,
    }).locator("xpath=../..");
    await expect(historyCard.getByText("E2E temporary co-owner verification", { exact: true })).toBeVisible();

    await page.goto(`/owners/${ASSIGNMENT_OWNER_ID}`);
    await expect(page.getByRole("heading", { name: ASSIGNMENT_OWNER })).toBeVisible();
    await page.getByRole("tab", { name: "Katastrid" }).click();
    const relationRows = page.locator(".flex.flex-col.justify-between.gap-3.rounded-lg.border.p-3");
    const parcelRow = relationRows.filter({ hasText: SHARED_CADASTRAL_CODE });
    await expect(parcelRow).toBeVisible();
    await expect(page.getByRole("button", { name: "Lisa seos" })).toBeVisible();
    await expect(parcelRow.getByText("Kaasomand · 2", { exact: true })).toBeVisible();

    page.once("dialog", dialog => dialog.accept("E2E cleanup temporary co-owner relation"));
    const endResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/ownership-relations/${ASSIGNMENT_OWNER_ID}/${created.parcelId}/end`)
        && response.request().method() === "POST",
    );
    await parcelRow.getByRole("button", { name: "Lõpeta seos" }).click();
    expect((await endResponsePromise).ok()).toBeTruthy();
    await expect(relationRows.filter({ hasText: SHARED_CADASTRAL_CODE })).toHaveCount(0);

    await page.goto("/admin");
    await page.getByRole("button", { name: "Omaniku–katastri seosed" }).click();
    await filterRelations(page, SHARED_CADASTRAL_CODE);
    await expect(page.getByRole("row", { name: new RegExp(`${ASSIGNMENT_OWNER}.*${SHARED_CADASTRAL_CODE}`) })).toContainText("Lõpetatud");
    await expect(page.getByRole("row", { name: new RegExp(`${DEAL_OWNER}.*${SHARED_CADASTRAL_CODE}`) })).toContainText("Aktiivne");
  });

  test("evaluator does not receive ownership mutation controls", async ({ page }) => {
    await login(page, e2eUsers.evaluator);
    await page.goto("/admin");
    await expect(page.getByRole("button", { name: "Omaniku–katastri seosed" })).toHaveCount(0);

    await page.goto(`/owners/${ASSIGNMENT_OWNER_ID}`);
    await page.getByRole("tab", { name: "Katastrid" }).click();
    await expect(page.getByRole("button", { name: "Lisa seos" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Lõpeta seos" })).toHaveCount(0);
  });
});
