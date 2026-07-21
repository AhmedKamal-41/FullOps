import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/renderWithProviders";
import { RequireOperatorAccess } from "./RequireOperatorAccess";
import { makeFakeAccessToken } from "../test/fakeAccessToken";

const signinRedirect = vi.fn();

vi.mock("react-oidc-context", () => ({
  useAuth: vi.fn(),
}));

import { useAuth } from "react-oidc-context";

describe("RequireOperatorAccess", () => {
  it("redirects to sign-in when the visitor isn't authenticated", () => {
    vi.mocked(useAuth).mockReturnValue({
      isLoading: false,
      isAuthenticated: false,
      activeNavigator: undefined,
      error: undefined,
      signinRedirect,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any);

    renderWithProviders(
      <RequireOperatorAccess>
        <div>Console content</div>
      </RequireOperatorAccess>,
    );

    expect(signinRedirect).toHaveBeenCalled();
    expect(screen.queryByText("Console content")).not.toBeInTheDocument();
  });

  it("shows a real Forbidden page for an authenticated visitor without the OPERATOR or ADMIN role", () => {
    vi.mocked(useAuth).mockReturnValue({
      isLoading: false,
      isAuthenticated: true,
      activeNavigator: undefined,
      error: undefined,
      user: { access_token: makeFakeAccessToken(["CUSTOMER"]) },
      signinRedirect,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any);

    renderWithProviders(
      <RequireOperatorAccess>
        <div>Console content</div>
      </RequireOperatorAccess>,
    );

    expect(screen.getByText("Forbidden")).toBeInTheDocument();
    expect(screen.queryByText("Console content")).not.toBeInTheDocument();
  });

  it("renders the console for an OPERATOR", () => {
    vi.mocked(useAuth).mockReturnValue({
      isLoading: false,
      isAuthenticated: true,
      activeNavigator: undefined,
      error: undefined,
      user: { access_token: makeFakeAccessToken(["OPERATOR"]) },
      signinRedirect,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any);

    renderWithProviders(
      <RequireOperatorAccess>
        <div>Console content</div>
      </RequireOperatorAccess>,
    );

    expect(screen.getByText("Console content")).toBeInTheDocument();
  });
});
