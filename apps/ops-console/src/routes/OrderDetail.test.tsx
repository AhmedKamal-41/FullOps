import { describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders } from "../test/renderWithProviders";
import { OrderDetail } from "./OrderDetail";

vi.mock("react-oidc-context", () => ({
  useAuth: () => ({ user: { access_token: "test-token" } }),
}));
vi.mock("../demoMode/DemoModeContext", () => ({ useDemoMode: () => false }));

const ORDER_ID = "a1111111-1111-4111-8111-111111111101";

function renderOrderDetail() {
  return renderWithProviders(
    <Routes>
      <Route path="/orders/:orderId" element={<OrderDetail />} />
    </Routes>,
    { route: `/orders/${ORDER_ID}` },
  );
}

describe("OrderDetail", () => {
  it("renders the order's end-to-end event timeline", async () => {
    renderOrderDetail();

    const timelineHeading = await screen.findByText("Event timeline");
    const timelineCard = timelineHeading.closest("div")!;
    expect(within(timelineCard).getAllByText("Dispatched").length).toBeGreaterThan(0);
    expect(within(timelineCard).getByText("Picking")).toBeInTheDocument();
  });

  it("requires a reason and confirmation before cancelling an order", async () => {
    const user = userEvent.setup();
    renderOrderDetail();

    const cancelButton = await screen.findByRole("button", { name: "Cancel order" });
    await user.click(cancelButton);

    const dialog = await screen.findByRole("dialog");
    const confirmButton = within(dialog).getByRole("button", { name: "Cancel order" });

    // No reason entered yet: the destructive action stays disabled.
    expect(confirmButton).toBeDisabled();

    await user.type(within(dialog).getByLabelText(/Reason/), "Customer requested cancellation");
    expect(confirmButton).toBeEnabled();

    await user.click(confirmButton);

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
