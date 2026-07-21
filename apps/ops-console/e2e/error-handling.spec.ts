import { test, expect } from "@playwright/test";
import { loginAs } from "./helpers/auth";

test("a failed API call shows a truthful error state, not a blank page or stale data", async ({
  page,
}) => {
  await page.route("**/api/v1/ops/kpis/overview**", (route) =>
    route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({ title: "Internal Server Error", status: 500 }),
    }),
  );

  await loginAs(page, "operator.demo", "OperatorDemo!123");

  await expect(page.getByText("Couldn't load this data")).toBeVisible();
});
