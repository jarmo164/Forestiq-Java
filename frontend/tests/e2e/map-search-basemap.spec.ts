import { expect, test } from "@playwright/test";
import { e2eUsers, login } from "./helpers/auth";

const CADASTRAL_CODE = "10001:001:0002";
const SEARCH_LABEL = "Otsi aadressi, katastritunnust või CRM omanikku";

async function openMapWorkspace(page: import("@playwright/test").Page) {
  await login(page, e2eUsers.admin);
  await page.goto("/map");
  await expect(page).toHaveURL(/\/map$/);
}

function parcelSection(page: import("@playwright/test").Page, title: string) {
  return page.locator("summary").filter({ hasText: title });
}

async function openSearchPanel(page: import("@playwright/test").Page) {
  const searchToggle = page.getByRole("button", { name: "Ava otsing ja filtrid" });
  if (await searchToggle.isVisible()) await searchToggle.click();
  return page.getByLabel(SEARCH_LABEL);
}

async function selectLocalCadastre(page: import("@playwright/test").Page) {
  const input = await openSearchPanel(page);
  const searchResponse = page.waitForResponse(item => item.url().includes("/api/v1/map/search?"));
  await input.fill(CADASTRAL_CODE);
  expect((await searchResponse).ok()).toBeTruthy();

  const result = page.getByRole("button", { name: new RegExp(CADASTRAL_CODE) }).first();
  await expect(result).toBeVisible();
  await result.click();
  await expect(page.getByLabel("Kinnistute kaart")).toHaveAttribute("data-selected-cadastral-code", CADASTRAL_CODE);
}

test.describe("map search and basemap isolation", () => {
  test("address result focuses map context without creating a parcel detail", async ({ page }) => {
    await openMapWorkspace(page);
    await page.route("**/api/v1/map/search?*", async route => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          results: [{
            type: "ADDRESS",
            displayLabel: "Harju maakond, Tallinn, Timuti tn 15",
            longitude: 24.75,
            latitude: 59.44,
            west: null,
            south: null,
            east: null,
            north: null,
            provider: "MAARUUM_INAKS",
            providerIdentity: "A1",
            cadastralCode: null,
            local: false,
          }],
        }),
      });
    });

    const input = await openSearchPanel(page);
    await input.fill("Timuti 15");
    await page.getByRole("button", { name: /Harju maakond, Tallinn, Timuti tn 15/ }).click();

    await expect(input).toHaveValue("");
    await expect(parcelSection(page, "Katastri põhiandmed")).toHaveCount(0);
    await expect(page.getByLabel("Kinnistute kaart")).toHaveAttribute("data-selected-cadastral-code", "");
    await expect(page).toHaveURL(/\/map$/);
  });

  test("address provider failure does not clear current parcel work", async ({ page }) => {
    await openMapWorkspace(page);
    await selectLocalCadastre(page);
    await expect(parcelSection(page, "Katastri põhiandmed")).toBeVisible();

    await page.route("**/api/v1/map/search?*", async route => {
      await route.fulfill({
        status: 502,
        contentType: "application/json",
        body: JSON.stringify({ code: "MAP_SEARCH_PROVIDER_UNAVAILABLE", message: "provider down" }),
      });
    });
    const input = await openSearchPanel(page);
    await input.fill("Katkine aadress");

    await expect(page.getByText("Aadressiotsing ei ole praegu saadaval", { exact: false })).toBeVisible();
    await expect(page.getByLabel("Kinnistute kaart")).toHaveAttribute("data-selected-cadastral-code", CADASTRAL_CODE);
    await expect(parcelSection(page, "Katastri põhiandmed")).toBeVisible();
  });

  test("basemap switch preserves selected parcel and detail state", async ({ page }) => {
    await openMapWorkspace(page);
    await selectLocalCadastre(page);

    const basemap = page.getByLabel("Vali Maa- ja Ruumiameti aluskaart");
    await expect(basemap).toBeVisible();
    await basemap.selectOption("ortofoto");

    await expect(basemap).toHaveValue("ortofoto");
    await expect(page.getByLabel("Kinnistute kaart")).toHaveAttribute("data-selected-cadastral-code", CADASTRAL_CODE);
    await expect(parcelSection(page, "Katastri põhiandmed")).toBeVisible();
  });
});
