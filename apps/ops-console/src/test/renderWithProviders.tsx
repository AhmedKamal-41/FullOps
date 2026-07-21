import { type ReactNode } from "react";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { render } from "@testing-library/react";
import { theme } from "../theme";

export function renderWithProviders(ui: ReactNode, { route = "/" }: { route?: string } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MantineProvider theme={theme} env="test">
        <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
}
