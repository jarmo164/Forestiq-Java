import { expect, test, type Page, type Response } from "@playwright/test";
import { e2eUsers, login } from "./helpers/auth";

const CADASTRAL_CODE = "10001:001:0002";

async function openSearchPanel(page: Page) {
  const searchToggle = page.getByRole("button", { name: "Ava otsing ja filtrid" });
  if (await searchToggle.isVisible()) await searchToggle.click();
  return page.getByLabel("Otsi aadressi, katastritunnust või CRM omanikku");
}

async function openExactCadastre(page: Page, cadastralCode: string) {
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
  expect((await summaryResponse).ok()).toBeTruthy();
}

function expectVectorTile(response: Response) {
  expect(response.ok(), response.url()).toBeTruthy();
  expect(response.headers()["content-type"]).toContain("application/vnd.mapbox-vector-tile");
}

test("MapLibre loads server-owned generation and parcel vector tiles", async ({ page }) => {
  const mapResponses: Response[] = [];
  page.on("response", response => {
    const url = response.url();
    if (url.includes("/api/v1/map/")) {
      mapResponses.push(response);
    }
  });

  await login(page, e2eUsers.admin);

  const generationResponse = page.waitForResponse(response =>
    response.url().includes("/api/v1/map/generation") && response.request().method() === "GET",
  );
  const overviewTileResponse = page.waitForResponse(response =>
    response.url().includes("/api/v1/map/land-parcels-overview/")
      && response.url().includes(".pbf")
      && response.request().method() === "GET",
  );

  await page.goto("/map");
  await expect(page).toHaveURL(/\/map$/);
  await expect(page.getByRole("heading", { name: "ForestIQ kaardipõhine katastritööruum" })).toBeVisible();

  const generation = await generationResponse;
  expect(generation.ok()).toBeTruthy();
  const generationBody = await generation.json() as { available: boolean; generationId?: string | null; representationVersion?: number };
  expect(generationBody.available).toBe(true);
  expect(generationBody.generationId).toBeTruthy();
  expect(generationBody.representationVersion).toBeGreaterThan(0);

  const map = page.getByLabel("Kinnistute kaart");
  await expect(map).not.toHaveAttribute("data-map-generation", "unavailable");
  await expect(map).toHaveAttribute("data-map-generation", new RegExp(`^${generationBody.generationId}:r\\d+$`));

  const overviewTile = await overviewTileResponse;
  expectVectorTile(overviewTile);
  expect(overviewTile.url()).not.toContain("generation=unavailable");

  const detailTileResponse = page.waitForResponse(response =>
    response.url().includes("/api/v1/map/land-parcels/")
      && response.url().includes(".pbf")
      && !response.url().includes("land-parcels-overview")
      && response.request().method() === "GET",
    { timeout: 20_000 },
  );
  await openExactCadastre(page, CADASTRAL_CODE);
  await expect(map).toHaveAttribute("data-selected-cadastral-code", CADASTRAL_CODE);

  const detailTile = await detailTileResponse;
  expectVectorTile(detailTile);
  expect(detailTile.url()).not.toContain("generation=unavailable");

  const unavailableRequests = mapResponses.filter(response => response.url().includes("generation=unavailable"));
  expect(unavailableRequests).toHaveLength(0);
  const failedTileResponses = mapResponses.filter(response =>
    response.url().includes(".pbf") && !response.ok()
  );
  expect(failedTileResponses.map(response => `${response.status()} ${response.url()}`)).toEqual([]);
});
