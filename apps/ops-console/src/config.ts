// The console is demo-first: it must always boot so the demo-mode probe can run and
// fall back to bundled fixtures when no backend is reachable (see DemoModeContext).
// So every setting has a sensible localhost default (matching .env.example) and any
// VITE_* value present in the environment overrides it. Nothing here throws — a
// misconfigured URL simply surfaces as an unreachable backend, i.e. demo mode.
function env(key: keyof ImportMetaEnv, fallback: string): string {
  return import.meta.env[key] || fallback;
}

export const config = {
  orderServiceUrl: env("VITE_ORDER_SERVICE_URL", "http://localhost:8081"),
  fulfillmentServiceUrl: env("VITE_FULFILLMENT_SERVICE_URL", "http://localhost:8084"),
  keycloakAuthority: env("VITE_KEYCLOAK_AUTHORITY", "http://localhost:8080/realms/fulfillops"),
  keycloakClientId: env("VITE_KEYCLOAK_CLIENT_ID", "fulfillops-console"),
};
