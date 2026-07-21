function requiredEnv(key: keyof ImportMetaEnv): string {
  const value = import.meta.env[key];
  if (!value) {
    throw new Error(`Missing required environment variable: ${key}`);
  }
  return value;
}

export const config = {
  orderServiceUrl: requiredEnv("VITE_ORDER_SERVICE_URL"),
  fulfillmentServiceUrl: requiredEnv("VITE_FULFILLMENT_SERVICE_URL"),
  keycloakAuthority: requiredEnv("VITE_KEYCLOAK_AUTHORITY"),
  keycloakClientId: requiredEnv("VITE_KEYCLOAK_CLIENT_ID"),
};
