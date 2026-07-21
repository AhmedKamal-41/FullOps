import { test, expect } from "@playwright/test";

test("falls back to clearly labeled demo data when the backend is unreachable", async ({
  page,
}) => {
  await page.route("**/actuator/health", (route) => route.abort("connectionrefused"));

  await page.goto("/");

  await expect(page.getByTestId("demo-mode-banner")).toBeVisible();
  await expect(page.getByText(/backend is unreachable/)).toBeVisible();
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(page.getByText("Orders received")).toBeVisible();
});
