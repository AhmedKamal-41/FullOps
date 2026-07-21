/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ORDER_SERVICE_URL: string;
  readonly VITE_FULFILLMENT_SERVICE_URL: string;
  readonly VITE_KEYCLOAK_AUTHORITY: string;
  readonly VITE_KEYCLOAK_CLIENT_ID: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
