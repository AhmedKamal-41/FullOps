import { expect, type Page } from "@playwright/test";

// The E2E suite runs against the console's self-contained demo mode: with no backend
// reachable (as in CI), the app boots straight into the operator UI backed by bundled
// example data, with no Keycloak login step. This helper enters the console and waits
// for it to be ready — signalled by the demo-data banner — so specs can start acting.
export async function enterConsole(page: Page) {
  await page.goto("/");
  await expect(page.getByTestId("demo-mode-banner")).toBeVisible();
}
