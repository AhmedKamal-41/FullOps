import { Center, Loader, MantineProvider, Text } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider } from "react-oidc-context";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { DemoModeProvider } from "./demoMode/DemoModeContext";
import { authProviderProps } from "./auth/authConfig";
import { RequireOperatorAccess } from "./auth/RequireOperatorAccess";
import { queryClient } from "./queryClient";
import { theme, cssVariablesResolver } from "./theme";
import { Shell } from "./components/Shell";
import { Overview } from "./routes/Overview";
import { WorkQueue } from "./routes/WorkQueue";
import { OrderDetail } from "./routes/OrderDetail";
import { Incidents } from "./routes/Incidents";
import { InventoryRisk } from "./routes/InventoryRisk";
import { FulfillmentBoard } from "./routes/FulfillmentBoard";
import { Callback } from "./routes/Callback";

import "@mantine/core/styles.css";
import "@mantine/charts/styles.css";
import "@mantine/notifications/styles.css";

function ConsoleRoutes() {
  return (
    <Shell>
      <Routes>
        <Route path="/" element={<Overview />} />
        <Route path="/work-queue" element={<WorkQueue />} />
        <Route path="/orders/:orderId" element={<OrderDetail />} />
        <Route path="/incidents" element={<Incidents />} />
        <Route path="/inventory-risk" element={<InventoryRisk />} />
        <Route path="/fulfillment-board" element={<FulfillmentBoard />} />
        <Route
          path="*"
          element={
            <Center py="xl">
              <Text c="dimmed">Page not found.</Text>
            </Center>
          }
        />
      </Routes>
    </Shell>
  );
}

export function App() {
  return (
    <MantineProvider
      theme={theme}
      cssVariablesResolver={cssVariablesResolver}
      defaultColorScheme="auto"
    >
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <AuthProvider {...authProviderProps}>
          <BrowserRouter>
            <DemoModeProvider>
              {(state) => {
                if (state === "probing") {
                  return (
                    <Center h="100vh">
                      <Loader aria-label="Checking backend connectivity" />
                    </Center>
                  );
                }
                return (
                  <Routes>
                    <Route path="/callback" element={<Callback />} />
                    <Route
                      path="/*"
                      element={
                        state === "demo" ? (
                          <ConsoleRoutes />
                        ) : (
                          <RequireOperatorAccess>
                            <ConsoleRoutes />
                          </RequireOperatorAccess>
                        )
                      }
                    />
                  </Routes>
                );
              }}
            </DemoModeProvider>
          </BrowserRouter>
        </AuthProvider>
      </QueryClientProvider>
    </MantineProvider>
  );
}
