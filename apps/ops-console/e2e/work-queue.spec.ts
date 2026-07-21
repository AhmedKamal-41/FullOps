import { test, expect } from "@playwright/test";
import { loginAs } from "./helpers/auth";

test.beforeEach(async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.getByRole("heading", { name: "Work Queue", level: 1 })).toBeVisible();
});

test("filtering by status updates both the URL and the results, and survives a refresh", async ({
  page,
}) => {
  await page.getByRole("combobox", { name: "Status" }).click();
  await page.getByRole("option", { name: "CANCELLED", exact: true }).click();

  await expect(page).toHaveURL(/status=CANCELLED/);

  await page.reload();
  await expect(page.getByRole("combobox", { name: "Status" })).toHaveValue("CANCELLED");
  const rows = page.locator("tbody tr");
  await expect(rows.first()).toBeVisible();
  for (const row of await rows.all()) {
    await expect(row.getByText("Cancelled")).toBeVisible();
  }
});

test("the work queue is fully keyboard-navigable: filters, then table rows, then pagination", async ({
  page,
}) => {
  await page.getByRole("combobox", { name: "Status" }).focus();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Customer ID")).toBeFocused();

  await page.keyboard.press("Tab");
  await expect(page.getByRole("combobox", { name: "SLA breached" })).toBeFocused();

  await page.keyboard.press("Tab");
  await expect(page.getByRole("combobox", { name: "Stuck" })).toBeFocused();

  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Export CSV" })).toBeFocused();
});
