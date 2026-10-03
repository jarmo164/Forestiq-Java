import { expect, test } from "@playwright/test";
import { e2eUsers, login, logout } from "./helpers/auth";

const DEAL_CADASTRAL_CODE = "10001:001:0002";
const DEAL_OWNER_NAME = "E2E Deal Owner";

async function openMapWorkspace(page: import("@playwright/test").Page, credentials = e2eUsers.admin) {
  await login(page, credentials);
  await page.goto("/map");
  await expect(page).toHaveURL(/\/map$/);
}

function parcelSection(page: import("@playwright/test").Page, title: string) {
  return page.locator("summary").filter({ hasText: title });
}

async function openSearchPanel(page: import("@playwright/test").Page) {
  const searchToggle = page.getByRole("button", { name: "Ava otsing ja filtrid" });
  if (await searchToggle.isVisible()) await searchToggle.click();
  return page.getByLabel("Otsi aadressi, katastritunnust või CRM omanikku");
}

async function openExactCadastre(page: import("@playwright/test").Page, cadastralCode: string) {
  const input = await openSearchPanel(page);
  const searchResponse = page.waitForResponse(response =>
    response.url().includes("/api/v1/map/search?") && response.request().method() === "GET",
  );
  await input.fill(cadastralCode);
  expect((await searchResponse).ok()).toBeTruthy();

  const result = page.getByRole("button", { name: new RegExp(cadastralCode) }).first();
  await expect(result).toBeVisible();
  const summaryResponse = page.waitForResponse(response =>
    response.url().includes(`/api/v1/map/land-parcels/${encodeURIComponent(cadastralCode)}/summary`)
      && response.request().method() === "GET",
  );
  await result.click();
  return summaryResponse;
}

test.describe.serial("map-first parcel workspace", () => {
  test("admin can enter the map workspace and exact cadastral search focuses the parcel", async ({ page }) => {
    await login(page, e2eUsers.admin);
    await expect(page).toHaveURL(/\/sales$/);
    await page.goto("/map");
    await expect(page.getByRole("heading", { name: "ForestIQ kaardipõhine katastritööruum" })).toBeVisible();
    await expect(page.getByLabel("Vali Maa- ja Ruumiameti aluskaart")).toBeVisible();

    await page.goto("/");
    await expect(page).toHaveURL(/\/sales$/);
    await page.goto("/map");

    const summaryResponse = await openExactCadastre(page, DEAL_CADASTRAL_CODE);
    expect(summaryResponse.ok()).toBeTruthy();
    const summary = await summaryResponse.json() as Record<string, unknown>;
    expect(summary.cadastralCode).toBe(DEAL_CADASTRAL_CODE);
    expect(summary).toHaveProperty("bboxWest");
    expect(summary).toHaveProperty("bboxNorth");

    await expect(page.getByText(DEAL_CADASTRAL_CODE, { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("region", { name: "Katastri kiire ülevaade" })).toBeVisible();
    await expect(page.getByText(DEAL_OWNER_NAME, { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Omaniku kaart" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Helista" })).toBeVisible();
    await expect(parcelSection(page, "Katastri põhiandmed")).toBeVisible();
    await expect(parcelSection(page, "Omanikud")).toBeVisible();
    await expect(parcelSection(page, "Metsateatised")).toBeVisible();
    await expect(page.getByLabel("Kinnistute kaart")).toHaveAttribute("data-selected-cadastral-code", DEAL_CADASTRAL_CODE);
    await expect(page.getByLabel("Kinnistute kaart")).toHaveAttribute("data-focused-cadastral-code", DEAL_CADASTRAL_CODE);
  });


  test("a GIS section failure stays local and leaves the parcel overview usable", async ({ page }) => {
    await openMapWorkspace(page);
    await page.route("**/api/v1/map/land-parcels/**/context", async route => {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ detail: "simulated context source outage" }),
      });
    });

    const summaryResponse = await openExactCadastre(page, DEAL_CADASTRAL_CODE);
    expect(summaryResponse.ok()).toBeTruthy();
    await parcelSection(page, "Maakasutus, ETAK ja ruumilised faktid").click();

    await expect(page.getByText("Andmete laadimine ebaõnnestus. Ülejäänud katastriinfo töötab edasi.")).toBeVisible();
    await expect(page.getByText(DEAL_CADASTRAL_CODE, { exact: true }).first()).toBeVisible();
    await expect(parcelSection(page, "Katastri põhiandmed")).toBeVisible();
    await expect(parcelSection(page, "Omanikud")).toBeVisible();
  });

  test("owner search keeps work in the map workspace", async ({ page }) => {
    await openMapWorkspace(page);
    const input = await openSearchPanel(page);
    await input.fill(DEAL_OWNER_NAME);
    await expect(page.getByRole("button", { name: new RegExp(DEAL_OWNER_NAME) })).toBeVisible();
    await page.getByRole("button", { name: new RegExp(DEAL_OWNER_NAME) }).click();
    await expect(page.getByText(new RegExp(`${DEAL_OWNER_NAME} · \\d+ kinnistut`))).toBeVisible();
    await expect(page).toHaveURL(/\/map$/);
  });

  test("registry refresh updates visible map data version without a page reload", async ({ page }) => {
    await openMapWorkspace(page);
    await openExactCadastre(page, DEAL_CADASTRAL_CODE);
    const map = page.getByLabel("Kinnistute kaart");
    await expect(map).toHaveAttribute("data-map-version", "0");

    await page.route("**/api/v1/registry/cadastres/**/refresh-all", async route => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          cadastre: { registry: "MAAAMET", cadastralCode: DEAL_CADASTRAL_CODE, recordsUpdated: 1, message: "ok", success: true },
          forestNotifications: { registry: "METSAREGISTER_NOTIFICATIONS", cadastralCode: DEAL_CADASTRAL_CODE, recordsUpdated: 2, message: "ok", success: true },
          forestPlan: { registry: "METSAREGISTER_PLAN", cadastralCode: DEAL_CADASTRAL_CODE, recordsUpdated: 3, message: "ok", success: true },
        }),
      });
    });

    const registrySection = parcelSection(page, "Ametlikud allikad ja käsitsi värskendamine");
    await registrySection.scrollIntoViewIfNeeded();
    await registrySection.click();
    await page.getByRole("button", { name: "Värskenda kõik" }).scrollIntoViewIfNeeded();
    await page.getByRole("button", { name: "Värskenda kõik" }).click();
    await expect(map).toHaveAttribute("data-map-version", "1");
    await expect(page.getByText(/Ametlikud andmed värskendatud/)).toBeVisible();
    await expect(page).toHaveURL(/\/map$/);
  });

  test("map CRM controls call existing owner and reminder APIs without leaving the map", async ({ page }) => {
    await openMapWorkspace(page, e2eUsers.callerA);
    const summaryResponse = await openExactCadastre(page, DEAL_CADASTRAL_CODE);
    expect(summaryResponse.ok()).toBeTruthy();
    const summary = await summaryResponse.json() as { ownerId?: string };
    expect(summary.ownerId).toBeTruthy();

    const workflowSection = parcelSection(page, "ForestIQ töövoog");
    await workflowSection.scrollIntoViewIfNeeded();
    await workflowSection.click();
    await expect(page.getByText("CRM töö", { exact: true })).toBeVisible();

    let statusBody: Record<string, unknown> | undefined;
    await page.route(`**/api/v1/owners/${summary.ownerId}/status`, async route => {
      statusBody = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({}) });
    });
    await page.getByLabel("Uus omaniku staatus").selectOption("CONTACTED");
    await page.getByLabel("Staatuse kommentaar").fill("Map-first E2E status");
    await page.getByRole("button", { name: "Salvesta staatus" }).click();
    await expect(page.getByText("Staatus uuendatud.", { exact: true })).toBeVisible();
    expect(statusBody?.status).toBe("CONTACTED");

    let noteBody: Record<string, unknown> | undefined;
    await page.route(`**/api/v1/owners/${summary.ownerId}/activities`, async route => {
      noteBody = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({ status: 204 });
    });
    await page.getByText("Lisa märkus", { exact: true }).click();
    await page.getByRole("textbox", { name: "CRM märkus", exact: true }).fill("Map-first E2E note");
    await page.getByRole("button", { name: "Salvesta CRM märkus", exact: true }).click();
    await expect(page.getByText("Märkus lisatud.", { exact: true })).toBeVisible();
    expect(noteBody?.content).toBe("Map-first E2E note");

    let reminderBody: Record<string, unknown> | undefined;
    await page.route("**/api/v1/reminders", async route => {
      reminderBody = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({ status: 204 });
    });
    await page.getByText("Lisa järeltegevus", { exact: true }).click();
    await page.getByLabel("Meeldetuletuse tekst").fill("Map-first E2E follow-up");
    await page.getByLabel("Meeldetuletuse aeg").fill("2030-01-02T10:30");
    await page.getByRole("button", { name: "Loo meeldetuletus" }).click();
    await expect(page.getByText("Meeldetuletus loodud.", { exact: true })).toBeVisible();
    expect(reminderBody?.cadastralCode).toBe(DEAL_CADASTRAL_CODE);
    expect(reminderBody?.ownerId).toBe(summary.ownerId);
    await expect(page).toHaveURL(/\/map$/);
  });

  test("caller can read an assigned parcel but does not see registry refresh controls", async ({ page }) => {
    await openMapWorkspace(page, e2eUsers.callerA);
    const summaryResponse = await openExactCadastre(page, DEAL_CADASTRAL_CODE);
    expect(summaryResponse.ok()).toBeTruthy();

    await expect(page.getByText(DEAL_CADASTRAL_CODE, { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Värskenda kõik" })).toHaveCount(0);
    const workflowSection = parcelSection(page, "ForestIQ töövoog");
    await workflowSection.scrollIntoViewIfNeeded();
    await workflowSection.click();
    await expect(page.getByText("CRM töö", { exact: true })).toBeVisible();

    await logout(page);
  });

  test("caller cannot open a parcel outside the assignment boundary", async ({ page }) => {
    await openMapWorkspace(page, e2eUsers.callerB);
    const summaryResponse = page.waitForResponse(response =>
      response.url().includes(`/api/v1/map/land-parcels/${encodeURIComponent(DEAL_CADASTRAL_CODE)}/summary`)
        && response.request().method() === "GET",
    );
    await page.goto(`/map?cadastralCode=${encodeURIComponent(DEAL_CADASTRAL_CODE)}`);
    expect([403, 404]).toContain((await summaryResponse).status());
    await expect(page.getByRole("button", { name: "Värskenda kõik" })).toHaveCount(0);
    await expect(page.getByText("CRM töö", { exact: true })).toHaveCount(0);
  });

  test("complete detail keeps multi-owner, bounded sections, source metadata and map highlight in one workspace", async ({ page }) => {
    await openMapWorkspace(page);

    await page.route(`**/api/v1/map/land-parcels/${encodeURIComponent(DEAL_CADASTRAL_CODE)}/summary`, route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        cadastralCode: DEAL_CADASTRAL_CODE,
        name: "Feature 328 contract parcel",
        address: "Test tee 1",
        municipality: "Tartu",
        county: "Tartumaa",
        areaHectares: 12.5,
        forestArea: 8.25,
        marked: true,
        ownerId: "11111111-1111-1111-1111-111111111111",
        ownerName: "Mari Mets",
        ownerStatus: "CONTACTED",
        ownerCount: 2,
        subPartCount: 1,
        activeNotificationCount: 1,
        notificationCount: 2,
        forestFeatureCount: 0,
        generationId: "22222222-2222-2222-2222-222222222222",
        sourceDate: "2026-09-15",
        owners: [
          { ownerId: "11111111-1111-1111-1111-111111111111", ownerName: "Mari Mets", ownerStatus: "CONTACTED", relationSource: "OWNER_PARCEL_RELATION" },
          { ownerId: "33333333-3333-3333-3333-333333333333", ownerName: "Jüri Mets", ownerStatus: "NEW", relationSource: "OWNER_PARCEL_RELATION" },
        ],
      }),
    }));
    await page.route("**/api/v1/cadastres/**/sub-parts/page?*", route => route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ items: [{ id: 77, cadastralCode: DEAL_CADASTRAL_CODE, subPartCode: 12, treeTypeCode: "KU", area: 2.5 }], nextCursor: null, hasMore: false }),
    }));
    await page.route("**/api/v1/cadastres/**/sub-parts/77/geometry", route => route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ id: 77, cadastralCode: DEAL_CADASTRAL_CODE, subPartCode: 12, polygon: "POLYGON((24 59,25 59,25 60,24 59))" }),
    }));
    await page.route("**/api/v1/cadastres/**/notifications/page?*", route => route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({
        items: [
          { id: 2, notificationNumber: "ACTIVE-1", cadastreSubpartCode: 12, archived: false, state: "VALID" },
          { id: 1, notificationNumber: "ARCHIVE-1", archived: true, state: "EXPIRED" },
        ],
        nextCursor: null, hasMore: false,
      }),
    }));
    await page.route("**/api/v1/map/land-parcels/**/context", route => route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({
        cadastralCode: DEAL_CADASTRAL_CODE,
        layerFacts: [{
          sourceKey: "MARU_ETAK_WOODY", title: "ETAK puittaimestik", datasetFamily: "ETAK",
          geometryType: "POLYGON", available: true, featureCount: 3, overlapAreaHa: 4.2,
          overlapPercent: 33.6, mapVisible: true, caveat: "Topograafiline maakate.",
          provenance: { generationId: "44444444-4444-4444-4444-444444444444", sourceDate: "2026-09-14", freshnessState: "FRESH" },
        }],
      }),
    }));

    expect((await openExactCadastre(page, DEAL_CADASTRAL_CODE)).ok()).toBeTruthy();

    await parcelSection(page, "Omanikud").scrollIntoViewIfNeeded();
    await expect(page.getByRole("link", { name: "Mari Mets" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Jüri Mets" })).toBeVisible();

    await parcelSection(page, "Mets ja eraldised").click();
    await expect(page.getByText("KU", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Näita polygon kaardil" }).click();
    await expect(page.getByLabel("Kinnistute kaart")).toHaveAttribute("data-detail-geometry-id", "subpart:77");

    await parcelSection(page, "Metsateatised").click();
    await expect(page.getByText("ACTIVE-1", { exact: true })).toBeVisible();
    await expect(page.getByText("ARCHIVE-1", { exact: true })).toBeVisible();

    await parcelSection(page, "Maakasutus, ETAK ja ruumilised faktid").click();
    await expect(page.getByText("ETAK puittaimestik", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("Topograafiline maakate.", { exact: true })).toBeVisible();

    await parcelSection(page, "Andmeallikad ja värskus").click();
    await expect(page.getByText(/Maa- ja Ruumiamet/).first()).toBeVisible();

    await page.getByRole("button", { name: "Sulge katastri detailvaade" }).click();
    await expect(page.getByLabel("Kinnistute kaart")).toHaveAttribute("data-detail-geometry-id", "");
  });


});
