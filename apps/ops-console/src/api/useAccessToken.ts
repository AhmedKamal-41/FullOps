import { useAuth } from "react-oidc-context";

// Safe to call in both live and demo mode: AuthProvider is always mounted (see App.tsx),
// but only live mode ever drives it to sign in, so this simply returns undefined in demo
// mode rather than throwing.
export function useAccessToken(): string | undefined {
  const auth = useAuth();
  return auth.user?.access_token;
}
