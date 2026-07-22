// Objective (defined before running — no number below this file is real until a run of this
// exact script produces it):
//   Measure Order Service's read-heavy operations work-queue endpoint
//   (GET /api/v1/ops/work-queue) under a small, local, single-node load: 10 virtual users for
//   30s. Target: p95 latency under 300ms and an HTTP error rate under 1%, on this developer's
//   laptop or CI runner — this is a read-model query over whatever orders already exist in the
//   local database, not a production capacity claim.
//
// Prerequisites: `make infra-up` and order-service running. The query result set size depends
// entirely on how many orders already exist locally — run scripts/seed-demo-data.sh first for a
// more realistic (non-empty) work queue.
//
// Run: k6 run --summary-export=docs/evidence/k6/ops-work-queue-summary.json tests/perf/ops-work-queue.js
import http from "k6/http";
import { check, sleep } from "k6";
import { fetchToken } from "./lib/auth.js";

const ORDER_SERVICE_URL = __ENV.ORDER_SERVICE_URL || "http://localhost:8081";
const OIDC_ISSUER_URI = __ENV.OIDC_ISSUER_URI || "http://localhost:8080/realms/fulfillops";
const OIDC_CLIENT_ID = __ENV.OIDC_CLI_CLIENT_ID || "fulfillops-cli";
const OIDC_CLIENT_SECRET = __ENV.OIDC_CLI_CLIENT_SECRET || "fulfillops-cli-local-secret";

export const options = {
  vus: 10,
  duration: "30s",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<300"],
  },
};

export function setup() {
  const operatorToken = fetchToken(OIDC_ISSUER_URI, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, "admin.demo", "AdminDemo!123");
  return { operatorToken };
}

export default function (data) {
  const response = http.get(`${ORDER_SERVICE_URL}/api/v1/ops/work-queue?page=0&size=20`, {
    headers: { Authorization: `Bearer ${data.operatorToken}` },
  });

  check(response, {
    "work queue returned (200)": (r) => r.status === 200,
  });

  sleep(0.2);
}
