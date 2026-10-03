import { expect, type Page } from "@playwright/test";

export const e2eUsers = {
  admin: { username: process.env.E2E_USERNAME ?? "admin", password: process.env.E2E_PASSWORD ?? "forestiq" },
  manager: { username: process.env.E2E_MANAGER_USERNAME ?? "e2e-manager", password: process.env.E2E_ROLE_PASSWORD ?? "forestiq-e2e" },
  callerA: { username: process.env.E2E_CALLER_A_USERNAME ?? "e2e-caller-a", password: process.env.E2E_ROLE_PASSWORD ?? "forestiq-e2e" },
  callerB: { username: process.env.E2E_CALLER_B_USERNAME ?? "e2e-caller-b", password: process.env.E2E_ROLE_PASSWORD ?? "forestiq-e2e" },
  evaluator: { username: process.env.E2E_EVALUATOR_USERNAME ?? "e2e-evaluator", password: process.env.E2E_ROLE_PASSWORD ?? "forestiq-e2e" },
} as const;

export async function login(page: Page, credentials = e2eUsers.admin) {
  await page.context().clearCookies();
  await page.goto("/login");
  await page.getByRole("link", { name: "Logi sisse" }).click();

  const username = page.locator("#username");
  await expect(username).toBeVisible({ timeout: 15_000 });
  await username.fill(credentials.username);
  await page.locator("#password").fill(credentials.password);
  await page.getByRole("button", { name: /Sign In|Logi sisse/i }).click();

  await expect(page.getByRole("link", { name: "Omanikud", exact: true })).toBeVisible();
}

export async function logout(page: Page) {
  await page.getByRole("button", { name: "Logi välja" }).click();
  await expect(page.getByRole("link", { name: "Logi sisse" })).toBeVisible();
}
