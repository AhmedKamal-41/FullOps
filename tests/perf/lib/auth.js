// Shared token-fetching helper for the k6 scripts in this directory. Every script obtains its
// own token in setup() rather than sharing one across VUs, since k6 setup() output is what gets
// safely handed to every VU.
import http from "k6/http";

export function fetchToken(issuerUri, clientId, clientSecret, username, password) {
  const response = http.post(`${issuerUri}/protocol/openid-connect/token`, {
    grant_type: "password",
    client_id: clientId,
    client_secret: clientSecret,
    username: username,
    password: password,
    scope: "openid fulfillops-api",
  });
  if (response.status !== 200) {
    throw new Error(`token request failed: ${response.status} ${response.body}`);
  }
  return response.json("access_token");
}
