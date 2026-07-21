import { useDemoMode } from "../demoMode/DemoModeContext";
import { useAccessToken } from "../api/useAccessToken";
import { isAdmin } from "./roles";

// Demo mode has no real identity to check roles against, so it shows every feature
// (including the ADMIN-only dead-letter panel) — it's static example data either way.
export function useIsAdmin(): boolean {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return isDemo || isAdmin(accessToken);
}
