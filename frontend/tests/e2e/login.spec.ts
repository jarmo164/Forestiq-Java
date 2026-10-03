import { expect, test } from "@playwright/test";
import { login } from "./helpers/auth";

test("shows the ForestIQ OIDC login entry point", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "Tere tulemast ForestIQ-sse" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Logi sisse" })).toBeVisible();
});

test("redirects unauthenticated workspace access to login", async ({ page }) => {
  await page.goto("/owners");
  await expect(page.getByRole("heading", { name: "Tere tulemast ForestIQ-sse" })).toBeVisible();
});

test("logs in through Keycloak and opens the caller-priority workspace", async ({ page }) => {
  await login(page);
  await expect(page).toHaveURL(/\/sales$/);
  await expect(page.getByRole("heading", { name: "Minu müügipult" })).toBeVisible();
});

test("loads the signed-in caller work queue", async ({ page }) => {
  await login(page);
  const workQueueResponse = page.waitForResponse(response =>
    response.url().includes("/api/v1/workflow/work-queue?view=TODAY") && response.request().method() === "GET",
  );
  await page.goto("/my-work");
  expect((await workQueueResponse).ok()).toBeTruthy();
  await expect(page.getByRole("heading", { name: "Minu töölaud" })).toBeVisible();
  await expect(page.getByText(/Tegevuste laadimine ebaõnnestus/)).toHaveCount(0);
});
