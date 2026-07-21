import { describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import { makeFakeAccessToken } from "../test/fakeAccessToken";
import { Incidents } from "./Incidents";

vi.mock("react-oidc-context", () => ({
  useAuth: () => ({ user: { access_token: makeFakeAccessToken(["OPERATOR"]) } }),
}));
vi.mock("../demoMode/DemoModeContext", () => ({ useDemoMode: () => false }));

describe("Incidents", () => {
  it("acknowledges an open incident after confirmation", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Incidents />);

    const row = (await screen.findByText("CANCELLATION_STUCK")).closest("tr")!;
    await user.click(within(row).getByRole("button", { name: "Acknowledge" }));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Acknowledge" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("resolves an incident with an optional note after confirmation", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Incidents />);

    const row = (await screen.findByText("CANCELLATION_STUCK")).closest("tr")!;
    await user.click(within(row).getByRole("button", { name: "Resolve" }));

    const dialog = await screen.findByRole("dialog");
    await user.type(
      within(dialog).getByLabelText("Resolution note (optional)"),
      "Retried manually and it went through.",
    );
    await user.click(within(dialog).getByRole("button", { name: "Resolve" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("switches tabs to filter incidents by status", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Incidents />);
    await screen.findByText("CANCELLATION_STUCK");

    await user.click(screen.getByRole("tab", { name: "Resolved" }));

    await waitFor(() =>
      expect(screen.getByRole("tab", { name: "Resolved" })).toHaveAttribute(
        "aria-selected",
        "true",
      ),
    );
  });
});
