import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { axe } from "jest-axe";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "../test/renderWithProviders";
import { server } from "../test/server";
import { config } from "../config";
import { Overview } from "./Overview";

vi.mock("react-oidc-context", () => ({
  useAuth: () => ({ user: { access_token: "test-token" } }),
}));
vi.mock("../demoMode/DemoModeContext", () => ({ useDemoMode: () => false }));

describe("Overview", () => {
  it("shows a loading state, then the real KPIs once they arrive", async () => {
    renderWithProviders(<Overview />);

    expect(screen.getAllByLabelText("Loading KPIs").length).toBeGreaterThan(0);

    expect(await screen.findByText("482")).toBeInTheDocument(); // ordersReceived
    expect(screen.getByText("401")).toBeInTheDocument(); // ordersCompleted
  });

  it("shows a truthful error state when the KPI request fails", async () => {
    server.use(
      http.get(`${config.orderServiceUrl}/api/v1/ops/kpis/overview`, () =>
        HttpResponse.json({ title: "Internal error", status: 500 }, { status: 500 }),
      ),
    );

    renderWithProviders(<Overview />);

    expect(await screen.findByText("Couldn't load this data")).toBeInTheDocument();
  });

  it("has no accessibility violations once loaded", async () => {
    const { container } = renderWithProviders(<Overview />);
    await waitFor(() => expect(screen.getByText("482")).toBeInTheDocument());

    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
