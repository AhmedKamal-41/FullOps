// jwt-decode only reads the payload, never verifies a signature, so a test token just
// needs the right shape — this is never sent to a real Keycloak.
function base64UrlEncode(value: object): string {
  return btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function makeFakeAccessToken(roles: string[]): string {
  const header = base64UrlEncode({ alg: "none", typ: "JWT" });
  const payload = base64UrlEncode({
    sub: "11111111-1111-4111-8111-111111111199",
    realm_access: { roles },
    exp: Math.floor(Date.now() / 1000) + 3600,
  });
  return `${header}.${payload}.`;
}
