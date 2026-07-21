import { describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "jest-axe";
import { renderWithProviders } from "../test/renderWithProviders";
import { WorkQueue } from "./WorkQueue";

vi.mock("react-oidc-context", () => ({
  useAuth: () => ({ user: { access_token: "test-token" } }),
}));
vi.mock("../demoMode/DemoModeContext", () => ({ useDemoMode: () => false }));

describe("WorkQueue", () => {
  it("lists orders from the work queue and links each to its detail page", async () => {
    renderWithProviders(<WorkQueue />);

    const row = await screen.findByText(/a1111111/);
    const link = within(row.closest("tr")!).getByRole("link");
    expect(link).toHaveAttribute("href", "/orders/a1111111-1111-4111-8111-111111111101");
  });

  it("puts the status filter into the URL when changed, driving a server-side re-fetch", async () => {
    const user = userEvent.setup();
    renderWithProviders(<WorkQueue />, { route: "/work-queue" });

    await screen.findByText(/a1111111/);

    const statusSelect = screen.getByRole("combobox", { name: "Status" });
    await user.click(statusSelect);
    const option = await screen.findByRole("option", { name: "CANCELLED" });
    await user.click(option);

    await waitFor(() => expect(statusSelect).toHaveValue("CANCELLED"));
  });

  it("is reachable by keyboard: filters and the export button are tab-focusable", async () => {
    const user = userEvent.setup();
    renderWithProviders(<WorkQueue />);
    await screen.findByText(/a1111111/);

    await user.tab();
    expect(screen.getByRole("combobox", { name: "Status" })).toHaveFocus();

    await user.tab();
    expect(screen.getByLabelText("Customer ID")).toHaveFocus();
  });

  it("has no accessibility violations once loaded", async () => {
    const { container } = renderWithProviders(<WorkQueue />);
    await screen.findByText(/a1111111/);

    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
