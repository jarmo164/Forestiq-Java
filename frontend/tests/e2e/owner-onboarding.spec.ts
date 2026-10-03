import { expect, test } from "@playwright/test";
import { e2eUsers, login } from "./helpers/auth";

test.describe("owner onboarding", () => {
  test("manager can create, detect duplicate and commit only READY CSV rows", async ({ page }) => {
    await login(page, e2eUsers.manager);
    await page.goto("/owners");
    await expect(page.getByRole("heading", { name: "Omanikud" })).toBeVisible();
    await expect(page.getByText("Omanike lisamine", { exact: true })).toBeVisible();

    const uniqueSuffix = Date.now().toString().slice(-8);
    const manualCode = `91${uniqueSuffix}`;
    const manualName = `E2E Manual ${uniqueSuffix}`;

    await page.getByRole("button", { name: "Lisa omanik", exact: true }).first().click();
    await page.getByLabel("Nimi *").fill(manualName);
    await page.getByLabel("Isiku- või registrikood *").fill(manualCode);
    await page.getByRole("textbox", { name: "Telefon", exact: true }).fill("+3725550111");
    const createResponsePromise = page.waitForResponse(response =>
      response.url().endsWith("/api/v1/owners") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Lisa omanik", exact: true }).last().click();
    expect((await createResponsePromise).status()).toBe(201);
    await expect(page.getByText("Omanik lisatud.")).toBeVisible();

    await page.getByLabel("Nimi *").fill(manualName);
    await page.getByLabel("Isiku- või registrikood *").fill(manualCode);
    const duplicateResponsePromise = page.waitForResponse(response =>
      response.url().endsWith("/api/v1/owners") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Lisa omanik", exact: true }).last().click();
    expect((await duplicateResponsePromise).status()).toBe(409);
    await expect(page.getByText(/Selle koodiga omanik on juba olemas/)).toBeVisible();
    await expect(page.getByRole("link", { name: manualName })).toBeVisible();

    await page.getByRole("button", { name: "Impordi failist", exact: true }).click();
    const importCode = `92${uniqueSuffix}`;
    const repeatedCode = `93${uniqueSuffix}`;
    const csv = [
      "Nimi;Registrikood;Telefon;organizationId",
      `E2E Import ${uniqueSuffix};${importCode};+3725550222;99999999-9999-9999-9999-999999999999`,
      `Repeated A;${repeatedCode};;99999999-9999-9999-9999-999999999999`,
      `Repeated B;${repeatedCode};;99999999-9999-9999-9999-999999999999`,
    ].join("\n");

    const inspectResponsePromise = page.waitForResponse(response =>
      response.url().includes("/api/v1/owners/imports/inspect") && response.request().method() === "POST",
    );
    await page.locator('input[type="file"]').setInputFiles({
      name: "owners.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv, "utf-8"),
    });
    expect((await inspectResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("owners.csv", { exact: true }).first()).toBeVisible();

    const previewResponsePromise = page.waitForResponse(response =>
      response.url().includes("/api/v1/owners/imports/preview") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Tee eelvaade / dry-run" }).click();
    expect((await previewResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Valmis 1", { exact: true })).toBeVisible();
    await expect(page.getByText("Vigased 2", { exact: true })).toBeVisible();
    await expect(page.getByText(/failis mitu korda/).first()).toBeVisible();

    const commitResponsePromise = page.waitForResponse(response =>
      response.url().includes("/api/v1/owners/imports/commit") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Impordi 1 valmis rida" }).click();
    expect((await commitResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Import lõpetatud", { exact: true })).toBeVisible();
    await expect(page.getByText(/Loodi 1, duplikaate 0, vigaseid 2/)).toBeVisible();
  });
});
