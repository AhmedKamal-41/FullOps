import { WebStorageStateStore } from "oidc-client-ts";
import type { AuthProviderProps } from "react-oidc-context";
import { config } from "../config";

// Tokens live in sessionStorage, never localStorage: they're gone as soon as the tab
// closes, which is the practical middle ground the phase brief asks for (a pure
// in-memory store would mean rebuilding oidc-client-ts's silent-renew handshake from
// scratch, for no real security gain over sessionStorage against script-injection —
// both are equally readable by JS running in the page).
export const authProviderProps: AuthProviderProps = {
  authority: config.keycloakAuthority,
  client_id: config.keycloakClientId,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: window.location.origin,
  scope: "openid profile fulfillops-api",
  response_type: "code",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, "/");
  },
};
