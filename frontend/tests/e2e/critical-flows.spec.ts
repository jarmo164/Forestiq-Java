import { expect, test } from "@playwright/test";
import { e2eUsers, login, logout } from "./helpers/auth";

const ASSIGNMENT_OWNER_ID = "bbbbbbbb-0000-0000-0000-000000000001";
const DEAL_OWNER_ID = "bbbbbbbb-0000-0000-0000-000000000002";

const assignmentOwner = "E2E Assignment Owner";
const dealOwner = "E2E Deal Owner";
let wonDealId = "";

test.describe.serial("critical CRM and deal flows", () => {
  test("manager assignment reaches caller queue and caller records status plus activity", async ({ page }) => {
    await login(page, e2eUsers.manager);
    await page.goto("/admin");
    await expect(page.getByRole("heading", { name: "Haldus" })).toBeVisible();

    const searchResponse = page.waitForResponse(response =>
      response.url().includes("/api/v1/admin/workdesk/search") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Koosta andmepõhine töövalik" }).click();
    expect((await searchResponse).ok()).toBeTruthy();

    await expect(page.getByText(assignmentOwner, { exact: true })).toBeVisible();
    await page.getByLabel(`Vali ${assignmentOwner}`).check();

    const assigneeSelect = page.locator("select").filter({
      has: page.locator("option", { hasText: "Vali kasutaja..." }),
    });
    await assigneeSelect.selectOption({ label: "E2E Caller A" });

    const assignmentResponse = page.waitForResponse(response =>
      response.url().includes("/api/v1/admin/workdesk/assign") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Määra (1)" }).click();
    expect((await assignmentResponse).status()).toBe(204);

    await logout(page);
    await login(page, e2eUsers.callerA);
    await page.goto("/my-work");
    await expect(page.getByRole("heading", { name: "Minu töölaud" })).toBeVisible();
    await expect(page.getByRole("link", { name: assignmentOwner })).toBeVisible();
    await page.getByRole("link", { name: assignmentOwner }).click();

    await expect(page.getByRole("heading", { name: assignmentOwner })).toBeVisible();
    await page.getByRole("button", { name: "Halda" }).click();
    await page.getByRole("button", { name: "Muuda staatust" }).click();
    const statusSelect = page.locator("select").filter({
      has: page.locator('option[value="CONTACTED"]'),
    });
    await statusSelect.selectOption("CONTACTED");
    await page.locator("label").filter({ hasText: "Kommentaar" }).locator("input").fill("E2E caller contacted assigned owner");

    const statusResponse = page.waitForResponse(response =>
      response.url().includes(`/api/v1/owners/${ASSIGNMENT_OWNER_ID}/status`) && response.request().method() === "PATCH",
    );
    await page.getByRole("button", { name: "Muuda", exact: true }).click();
    expect((await statusResponse).ok()).toBeTruthy();
    await expect(page.getByText("Kontakteeritud", { exact: true })).toBeVisible();

    await page.getByRole("tab", { name: "Ajalugu" }).click();
    const note = "E2E caller activity note";
    await page.getByPlaceholder("Lisa märkus...").fill(note);
    const activityResponse = page.waitForResponse(response =>
      response.url().includes(`/api/v1/owners/${ASSIGNMENT_OWNER_ID}/activities`) && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Lisa märkus" }).click();
    expect((await activityResponse).ok()).toBeTruthy();
    await expect(page.getByText(note, { exact: true })).toBeVisible();
  });

  test("CALLER plus VIEWER cannot cross the owner assignment boundary", async ({ page }) => {
    await login(page, e2eUsers.callerB);

    const forbiddenOwnerResponse = page.waitForResponse(response =>
      response.url().endsWith(`/api/v1/owners/${DEAL_OWNER_ID}/360`) && response.request().method() === "GET",
    );
    await page.goto(`/owners/${DEAL_OWNER_ID}`);
    expect((await forbiddenOwnerResponse).status()).toBe(403);
    await expect(page.getByText("Sul puudub selle tegevuse jaoks õigus", { exact: false })).toBeVisible();
  });

  test("qualification flows through valuation, offer, counteroffer and win", async ({ page }) => {
    await login(page, e2eUsers.callerA);
    await page.goto(`/owners/${DEAL_OWNER_ID}`);
    await expect(page.getByRole("heading", { name: dealOwner })).toBeVisible();
    await page.getByRole("tab", { name: "ForestIQ" }).click();

    await page.getByRole("button", { name: "Kvalifitseeri huvi" }).click();
    await expect(page.getByText("Kvalifitseeri huvi ja saada hindamisse", { exact: true })).toBeVisible();
    await page.getByLabel("Kõne kokkuvõte ja kliendi eesmärk *")
      .fill("E2E klient soovib müüa valitud metsakinnistu ja ootab hinnangut.");

    const createDealResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/deals/owners/${DEAL_OWNER_ID}`) && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Loo juhtum ja saada hindamisse" }).click();
    const createDealResponse = await createDealResponsePromise;
    expect(createDealResponse.ok()).toBeTruthy();
    const createdDeal = await createDealResponse.json() as { id: string };
    wonDealId = createdDeal.id;
    expect(wonDealId).toBeTruthy();

    await logout(page);
    await login(page, e2eUsers.evaluator);
    await page.goto("/evaluation-workdesk");
    await expect(page.getByRole("heading", { name: "Evaluaatori töölaud" })).toBeVisible();

    const evaluationRow = page.locator("tr").filter({ hasText: dealOwner });
    await expect(evaluationRow).toBeVisible();
    await expect(evaluationRow.getByText("Määramata", { exact: true })).toBeVisible();

    const claimResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/deals/${wonDealId}/evaluation-assignment/claim`) && response.request().method() === "POST",
    );
    await evaluationRow.getByRole("button", { name: "Võta töö endale" }).click();
    expect((await claimResponsePromise).ok()).toBeTruthy();
    await expect(evaluationRow.getByText("Minu töö", { exact: true })).toBeVisible();

    await evaluationRow.getByRole("button", { name: "Ava hindamine" }).click();
    await page.getByLabel("Soovituslik ostuhind (€) *").fill("125000");
    await page.getByLabel("Väärtuse alampiir (€)").fill("115000");
    await page.getByLabel("Väärtuse ülempiir (€)").fill("135000");

    const evaluationResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/deals/${wonDealId}/evaluations`) && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Salvesta otsus ja saada müüki" }).click();
    expect((await evaluationResponsePromise).ok()).toBeTruthy();

    await logout(page);
    await login(page, e2eUsers.callerA);
    await page.goto(`/owners/${DEAL_OWNER_ID}`);
    await expect(page.getByRole("heading", { name: dealOwner })).toBeVisible();
    await page.getByRole("tab", { name: "ForestIQ" }).click();
    await expect(page.getByText("Hinnastatud", { exact: true }).first()).toBeVisible();
    await page.getByRole("button", { name: "Detailid" }).first().click();
    await expect(page.getByText("Soovituslik ostuhind:")).toBeVisible();

    await page.getByLabel("Pakkumise summa (€) *").fill("125000");
    const prepareResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/deals/${wonDealId}/commercial/offers`) && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Valmista uus pakkumine" }).click();
    expect((await prepareResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Pakkumine ette valmistatud", { exact: true }).first()).toBeVisible();

    const sendResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/deals/${wonDealId}/commercial/offers/send`) && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Märgi pakkumine saadetuks" }).click();
    expect((await sendResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Pakkumine saadetud", { exact: true }).first()).toBeVisible();

    await page.getByLabel("Kliendi vastupakkumine (€) *").fill("132000");
    await page.getByLabel("Vastupakkumise märkus").fill("E2E klient tegi vastupakkumise");
    const counterResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/deals/${wonDealId}/commercial/counteroffers`) && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Salvesta kliendi vastupakkumine" }).click();
    expect((await counterResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Läbirääkimised", { exact: true }).first()).toBeVisible();
    await expect(page.getByText(/v2 · 132/)).toBeVisible();

    await page.getByRole("button", { name: "Märgi võidetuks" }).click();
    const winResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/deals/${wonDealId}/commercial/won`) && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Kinnita" }).click();
    expect((await winResponsePromise).ok()).toBeTruthy();
    await expect(page.getByText("Võidetud", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("Aktsepteeritud", { exact: true })).toBeVisible();
  });

  test("registry recovery and deal-backed PDF contract work through the running stack", async ({ page }) => {
    await login(page, e2eUsers.admin);
    const freshnessResponsePromise = page.waitForResponse(response =>
      response.url().includes("/api/v1/registry/freshness") && response.request().method() === "GET",
    );
    await page.goto("/data-freshness");
    expect((await freshnessResponsePromise).ok()).toBeTruthy();
    await expect(page.getByRole("heading", { name: "Andmete värskus" })).toBeVisible();

    const recoveryResponsePromise = page.waitForResponse(response =>
      response.url().includes("/api/v1/registry/freshness/recover") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Taasta aegunud andmed" }).click();
    const recoveryResponse = await recoveryResponsePromise;
    expect(recoveryResponse.ok()).toBeTruthy();
    await expect(page.getByText(/Aegunud andmete taastamispartii lõpetati|Teine registri värskendus töötab juba/)).toBeVisible();

    expect(wonDealId).toBeTruthy();
    await page.goto(`/owners/${DEAL_OWNER_ID}`);
    await expect(page.getByRole("heading", { name: dealOwner })).toBeVisible();
    await page.getByRole("tab", { name: "ForestIQ" }).click();
    await page.getByRole("button", { name: "Detailid" }).first().click();
    await expect(page.getByRole("link", { name: "Loo leping" })).toBeVisible();

    const draftResponsePromise = page.waitForResponse(response =>
      response.url().includes(`/api/v1/contracts/deals/${wonDealId}/draft`) && response.request().method() === "GET",
    );
    await page.getByRole("link", { name: "Loo leping" }).click();
    expect((await draftResponsePromise).ok()).toBeTruthy();
    await expect(page).toHaveURL(new RegExp(`/contracts\\?dealId=${wonDealId}`));
    await expect(page.getByText("Eeltäidetud aktsepteeritud pakkumisest", { exact: true })).toBeVisible();
    await expect(page.getByLabel("Hind (€)")).toHaveValue("132000");
    await expect(page.getByLabel("Tingimused")).toHaveValue("E2E klient tegi vastupakkumise");
    await expect(page.getByLabel("Nimi *")).toHaveValue(dealOwner);
    await expect(page.getByLabel("Katastritunnus 1 *")).toHaveValue("10001:001:0002");

    const contractNumber = `E2E-${Date.now()}`;
    await page.getByLabel("Lepingu number *").fill(contractNumber);
    await page.getByRole("checkbox", { name: /IBAN kontrollitud/ }).check();
    await page.getByRole("checkbox", { name: /Õige lepingumall kontrollitud/ }).check();
    const generateResponsePromise = page.waitForResponse(response =>
      response.url().includes("/api/v1/contracts/generate") && response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Genereeri PDF" }).click();
    const generateResponse = await generateResponsePromise;
    expect(generateResponse.ok()).toBeTruthy();
    const generated = await generateResponse.json() as { dealId?: string; offerEntryId?: string };
    expect(generated.dealId).toBe(wonDealId);
    expect(generated.offerEntryId).toBeTruthy();

    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", { name: /Laadi alla .*\.pdf/i }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/\.pdf$/i);
    await expect(page.getByText(contractNumber, { exact: true })).toBeVisible();
    await expect(page.getByText(new RegExp(`Deal ${wonDealId.slice(0, 8)}`))).toBeVisible();
  });
});
