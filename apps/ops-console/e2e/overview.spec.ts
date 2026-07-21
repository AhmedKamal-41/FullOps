import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { loginAs } from "./helpers/auth";

test.beforeEach(async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
});

test("Overview loads real KPIs, a throughput chart, backlog, and recent incidents", async ({
  page,
}) => {
  await expect(page.getByText("Orders received")).toBeVisible();
  await expect(page.getByText("Order throughput")).toBeVisible();
  await expect(page.getByText("Backlog by stage / SLA breaches")).toBeVisible();
  await expect(page.getByText("Recent incidents")).toBeVisible();

  await expect(page.getByText("View as table").first()).toBeVisible();
});

test("Overview has no automatically detectable accessibility violations", async ({ page }) => {
  await expect(page.getByText("Orders received")).toBeVisible();

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});
