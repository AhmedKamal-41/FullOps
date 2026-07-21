import { test, expect } from "@playwright/test";
import { loginAs } from "./helpers/auth";

test.beforeEach(async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await page.getByRole("link", { name: "Incidents" }).click();
  await expect(page.getByRole("heading", { name: "Incidents", level: 1 })).toBeVisible();
});

test("acknowledging an open incident moves it out of the Open tab", async ({ page }) => {
  const openRows = page.locator("tbody tr");
  const countBefore = await openRows.count();
  expect(countBefore).toBeGreaterThan(0);

  await openRows.first().getByRole("button", { name: "Acknowledge" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Acknowledge" }).click();
  await expect(dialog).not.toBeVisible();

  await expect(openRows).toHaveCount(countBefore - 1);

  await page.getByRole("tab", { name: "Assigned" }).click();
  await expect(page.locator("tbody tr").first()).toBeVisible();
});

test("resolving an incident with a note moves it to the Resolved tab", async ({ page }) => {
  const row = page.locator("tbody tr").first();
  await expect(row).toBeVisible();

  await row.getByRole("button", { name: "Resolve" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel(/Resolution note/).fill("Resolved during E2E test run");
  await dialog.getByRole("button", { name: "Resolve" }).click();
  await expect(dialog).not.toBeVisible();

  await page.getByRole("tab", { name: "Resolved" }).click();
  await expect(page.locator("tbody tr").first()).toBeVisible();
});
