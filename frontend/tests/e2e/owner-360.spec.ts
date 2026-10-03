import { expect, test } from "@playwright/test";
import { e2eUsers, login } from "./helpers/auth";

const OWNER_ID = "bbbbbbbb-0000-0000-0000-000000000002";
const OWNER_NAME = "E2E Deal Owner";

test.describe("owner 360 workspace", () => {
  test("loads the summary first and heavy sections independently", async ({ page }) => {
    await login(page, e2eUsers.manager);

    const overviewResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`/api/v1/owners/${OWNER_ID}/360`) && response.request().method() === "GET",
    );
    await page.goto(`/owners/${OWNER_ID}`);
    expect((await overviewResponsePromise).ok()).toBeTruthy();

    await expect(page.getByRole("heading", { name: OWNER_NAME })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Ülevaade" })).toHaveAttribute("aria-selected", "true");
    await expect(page.getByText("Aktiivne portfell", { exact: true })).toBeVisible();
    await expect(page.getByText("Andmete seis", { exact: true })).toBeVisible();

    await page.getByRole("tab", { name: "Katastrid" }).click();
    await expect(page.getByText("Katastriseosed", { exact: true })).toBeVisible();

    const mapResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`/api/v1/owners/${OWNER_ID}/360/map`) && response.request().method() === "GET",
    );
    await page.getByRole("tab", { name: "Kaart" }).click();
    expect((await mapResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Omaniku portfell kaardil", { exact: true })).toBeVisible();

    const historyResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/owners/${OWNER_ID}/360/history`) && response.request().method() === "GET",
    );
    await page.getByRole("tab", { name: "Ajalugu" }).click();
    expect((await historyResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Kronoloogiline ajalugu", { exact: true })).toBeVisible();

    const workflowResponsePromise = page.waitForResponse(response =>
      response.url().endsWith(`/api/v1/owners/${OWNER_ID}/360/workflow`) && response.request().method() === "GET",
    );
    await page.getByRole("tab", { name: "ForestIQ" }).click();
    expect((await workflowResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("ForestIQ töövoog", { exact: true })).toBeVisible();

    await page.getByRole("tab", { name: "Andmeallikad" }).click();
    await expect(page.getByText("Andmeallikad ja värskus", { exact: true })).toBeVisible();
  });
});
