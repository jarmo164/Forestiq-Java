import { expect, test } from "@playwright/test";
import { e2eUsers, login } from "./helpers/auth";

const CADASTRAL_CODE = "10001:001:0002";

const registry = {
  manifestVersion: "2026-09-06-e2e",
  layers: [
    {
      key: "metsaregister:mue",
      title: "Metsa uuendamise ekspertiisid MUE",
      provider: "Metsaregister",
      category: "METSAREGISTER_EXTERNAL",
      businessMode: "EXTERNAL_MAP",
      transport: "WMS",
      sourceLayer: null,
      minZoom: 9,
      maxZoom: 20,
      defaultOpacity: 0.5,
      order: 120,
      defaultVisible: false,
      queryable: false,
      mandatory: false,
      attribution: "Metsaregister / Keskkonnaagentuur",
      freshnessTarget: "PROVIDER",
      authorizationScope: "AUTHENTICATED",
      styleKind: "RASTER",
      available: true,
      tileUrlTemplate: "/api/v1/map/metsaregister/wms/metsaregister:mue?bbox={bbox-epsg-3857}&width=256&height=256&crs=EPSG:3857&format=image/png&zoom={z}",
    },
    {
      key: "metsaregister:katsealad",
      title: "Katsealad",
      provider: "Metsaregister",
      category: "METSAREGISTER_EXTERNAL",
      businessMode: "EXTERNAL_MAP",
      transport: "WMS",
      sourceLayer: null,
      minZoom: 7,
      maxZoom: 20,
      defaultOpacity: 0.45,
      order: 100,
      defaultVisible: false,
      queryable: false,
      mandatory: false,
      attribution: "Metsaregister / Keskkonnaagentuur",
      freshnessTarget: "PROVIDER",
      authorizationScope: "AUTHENTICATED",
      styleKind: "RASTER",
      available: true,
      tileUrlTemplate: "/api/v1/map/metsaregister/wms/metsaregister:katsealad?bbox={bbox-epsg-3857}&width=256&height=256&crs=EPSG:3857&format=image/png&zoom={z}",
    },
  ],
};

function parcelSection(page: import("@playwright/test").Page, title: string) {
  return page.locator("summary").filter({ hasText: title });
}

async function openSearchPanel(page: import("@playwright/test").Page) {
  const searchToggle = page.getByRole("button", { name: "Ava otsing ja filtrid" });
  if (await searchToggle.isVisible()) await searchToggle.click();
  return page.getByLabel("Otsi aadressi, katastritunnust või CRM omanikku");
}

async function openExactCadastre(page: import("@playwright/test").Page) {
  const input = await openSearchPanel(page);
  const searchResponse = page.waitForResponse(response =>
    response.url().includes("/api/v1/map/search?") && response.request().method() === "GET",
  );
  await input.fill(CADASTRAL_CODE);
  expect((await searchResponse).ok()).toBeTruthy();

  const result = page.getByRole("button", { name: new RegExp(CADASTRAL_CODE) }).first();
  await expect(result).toBeVisible();
  const summaryResponse = page.waitForResponse(response =>
    response.url().includes(`/api/v1/map/land-parcels/${encodeURIComponent(CADASTRAL_CODE)}/summary`)
      && response.request().method() === "GET",
  );
  await result.click();
  expect((await summaryResponse).ok()).toBeTruthy();

  const closeSearch = page.getByRole("button", { name: "Sulge otsing ja filtrid" });
  if (await closeSearch.isVisible()) await closeSearch.click();
}

test("Metsaregister overlays keep selection, respect descriptor order/zoom and fail without blocking local data", async ({ page }) => {
  // Make map startup deterministic and independent of the external MapTiler style service.
  await page.route("https://api.maptiler.com/maps/**", async route => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ version: 8, sources: {}, layers: [] }),
    });
  });
  await page.route("**/api/v1/map/metsaregister/layers", async route => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(registry) });
  });
  await page.route("**/api/v1/map/context/layers", async route => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });
  await page.route("**/api/v1/map/metsaregister/wms/**", async route => {
    await route.fulfill({ status: 502, contentType: "application/json", body: JSON.stringify({ code: "METSAREGISTER_MAP_UNAVAILABLE" }) });
  });

  await login(page, e2eUsers.admin);
  await page.goto("/map");
  await expect(page).toHaveURL(/\/map$/);
  await openExactCadastre(page);

  const map = page.getByLabel("Kinnistute kaart");
  await expect(map).toHaveAttribute("data-selected-cadastral-code", CADASTRAL_CODE);
  await expect(parcelSection(page, "Katastri põhiandmed")).toBeVisible();

  const control = page.getByLabel("Kaardikihtide töölaud");
  await expect(control).toBeVisible();
  await control.getByRole("button", { name: "Ava kaardikihid" }).click();
  await expect(control.getByRole("button", { name: "Sulge kaardikihid" })).toBeVisible();
  await expect(control).toContainText(/Katsealad[\s\S]*Metsa uuendamise ekspertiisid MUE/);

  const visibility = control.getByLabel("Katsealad nähtavus");
  const opacity = control.getByLabel("Katsealad läbipaistvus");
  await visibility.click();
  await expect(visibility).toHaveAttribute("aria-pressed", "true");
  await control.getByLabel("Katsealad info").click();
  await expect(control).toContainText("zoom 7–20");
  await expect(control).toContainText("Metsaregister / Keskkonnaagentuur");
  await expect(opacity).toBeEnabled();
  await opacity.evaluate((element: HTMLInputElement) => {
    element.value = "25";
    element.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await expect(opacity).toHaveValue("25");

  // The external overlay mutates only the existing MapLibre style: selected
  // parcel and local domain detail must remain usable.
  await expect(map).toHaveAttribute("data-selected-cadastral-code", CADASTRAL_CODE);
  await expect(parcelSection(page, "Katastri põhiandmed")).toBeVisible();
  await expect(control.getByRole("status")).toContainText("ForestIQ kohalikud kihid ja kinnistuandmed töötavad edasi", { timeout: 10_000 });

  await visibility.click();
  await expect(visibility).toHaveAttribute("aria-pressed", "false");
  await expect(map).toHaveAttribute("data-selected-cadastral-code", CADASTRAL_CODE);
  await expect(page).toHaveURL(/\/map$/);
});
