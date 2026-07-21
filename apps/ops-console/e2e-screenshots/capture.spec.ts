import { test, expect } from "@playwright/test";
import { loginAs } from "../e2e/helpers/auth";

// Captures docs/screenshots/*.png from the real seeded local stack, logged in as
// operator.demo. Not part of the normal test run — see package.json's "screenshots"
// script. Every screenshot shows only fictional demo data (see scripts/seed-demo-data.sh
// and infra/keycloak/realm-export.json) — no real names, emails, or tokens.

test("capture login screen", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByLabel("Username or email")).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/01-login.png" });
});

test("capture Overview", async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(page.getByText("Orders received")).toBeVisible();
  await page.waitForTimeout(500);
  await page.screenshot({ path: "../../docs/screenshots/02-overview.png" });
});

test("capture Work Queue", async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.getByRole("heading", { name: "Work Queue", level: 1 })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/03-work-queue.png" });
});

test("capture Order Detail", async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.locator("tbody tr").first().getByRole("link").click();
  await expect(page.getByText("Event timeline")).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/04-order-detail.png" });
});

test("capture Incidents", async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await page.getByRole("link", { name: "Incidents" }).click();
  await expect(page.getByRole("heading", { name: "Incidents", level: 1 })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/05-incidents.png" });
});

test("capture Inventory Risk", async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await page.getByRole("link", { name: "Inventory Risk" }).click();
  await expect(page.getByRole("heading", { name: "Inventory Risk", level: 1 })).toBeVisible();
  await expect(page.getByText("Low-stock SKUs")).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/06-inventory-risk.png" });
});

test("capture Fulfillment Board", async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await page.getByRole("link", { name: "Fulfillment Board" }).click();
  await expect(page.getByRole("heading", { name: "Fulfillment Board", level: 1 })).toBeVisible();
  await page.waitForTimeout(500);
  await page.screenshot({ path: "../../docs/screenshots/07-fulfillment-board.png" });
});
