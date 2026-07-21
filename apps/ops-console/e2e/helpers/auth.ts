import type { Page } from "@playwright/test";

// Real Authorization Code + PKCE flow against the real local Keycloak — no mocked
// auth anywhere in the E2E suite. Keycloak's default login form ships with these
// exact element ids regardless of realm.
export async function loginAs(page: Page, username: string, password: string) {
  await page.goto("/");
  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign In" }).click();
}
