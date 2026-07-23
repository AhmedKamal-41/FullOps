import { test, expect } from "@playwright/test";
import { enterConsole } from "./helpers/auth";

test.beforeEach(async ({ page }) => {
  await enterConsole(page);
});

test("order timeline renders for a real seeded order", async ({ page }) => {
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.getByRole("heading", { name: "Work Queue", level: 1 })).toBeVisible();

  const firstOrderLink = page.locator("tbody tr").first().getByRole("link");
  await firstOrderLink.click();

  await expect(page.getByText("Event timeline")).toBeVisible();
  await expect(page.getByText("Correlation ID:")).toBeVisible();
});

test("cancelling an order requires a reason and shows a confirmation before acting", async ({
  page,
}) => {
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.getByRole("heading", { name: "Work Queue", level: 1 })).toBeVisible();

  const firstOrderLink = page.locator("tbody tr").first().getByRole("link");
  await expect(firstOrderLink).toBeVisible();
  await firstOrderLink.click();

  await page.getByRole("button", { name: "Cancel order" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();

  const confirmButton = dialog.getByRole("button", { name: "Cancel order" });
  await expect(confirmButton).toBeDisabled();

  await dialog.getByLabel("Reason").fill("E2E test cancellation");
  await expect(confirmButton).toBeEnabled();
  await confirmButton.click();

  await expect(dialog).not.toBeVisible();
  await expect(page.getByText(/Cancellation Pending|Cancelled/).first()).toBeVisible();
});
