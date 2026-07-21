import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { config } from "../config";

type ConnectivityState = "probing" | "live" | "demo";

const DemoModeContext = createContext<boolean | null>(null);

// The only place any mock data can enter the app: if the real backend is unreachable at
// the network level (DNS failure, connection refused, timeout — never a real HTTP
// response, even an error one, since a 401/403/500 means the backend IS there), we fall
// back to bundled fixtures and say so with a permanent banner. Normal operation never
// takes this path.
async function probeBackendReachable(): Promise<boolean> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 3000);
  try {
    await fetch(`${config.orderServiceUrl}/actuator/health`, { signal: controller.signal });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timeout);
  }
}

export function useDemoMode(): boolean {
  const value = useContext(DemoModeContext);
  if (value === null) {
    throw new Error("useDemoMode must be used within a DemoModeProvider");
  }
  return value;
}

export function DemoModeProvider({
  children,
}: {
  children: (state: ConnectivityState) => ReactNode;
}) {
  const [state, setState] = useState<ConnectivityState>("probing");

  useEffect(() => {
    let cancelled = false;
    probeBackendReachable().then((reachable) => {
      if (!cancelled) {
        setState(reachable ? "live" : "demo");
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (state === "probing") {
    return children("probing");
  }

  return (
    <DemoModeContext.Provider value={state === "demo"}>{children(state)}</DemoModeContext.Provider>
  );
}
