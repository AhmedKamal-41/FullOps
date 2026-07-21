import { test, expect } from "@playwright/test";
import { loginAs } from "./helpers/auth";

test("operator.demo can sign in through real Keycloak and lands on Overview", async ({ page }) => {
  await loginAs(page, "operator.demo", "OperatorDemo!123");

  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
});

test("customer.demo is authenticated but forbidden from the console", async ({ page }) => {
  await loginAs(page, "customer.demo", "CustomerDemo!123");

  await expect(page.getByRole("heading", { name: "Forbidden" })).toBeVisible();
  await expect(page.getByText(/OPERATOR or ADMIN role/)).toBeVisible();
});
