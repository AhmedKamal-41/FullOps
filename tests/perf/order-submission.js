// Objective (defined before running, per CLAUDE.md — no number below this file is real until a
// run of this exact script produces it):
//   Measure Order Service's order-submission endpoint (POST /api/v1/orders) under a small,
//   local, single-node load: 10 virtual users for 30s, ramping up over 5s and down over 5s.
//   Target: p95 latency under 500ms and an HTTP error rate under 1%, on this developer's laptop
//   or CI runner (single Compose stack, no scaling) — this is not a production capacity claim,
//   only a repeatable regression baseline for this codebase.
//
// Prerequisites: `make infra-up`, then all four services running (`make run-order` etc, or
// `make smoke` variants), with at least one fictional product created and stocked with enough
// quantity to survive the whole run (see setup() below — it stocks 100000 units itself).
//
// Run: k6 run tests/perf/order-submission.js
// Summary: written to docs/evidence/k6/order-submission-summary.json (see --summary-export below,
// or run with: k6 run --summary-export=docs/evidence/k6/order-submission-summary.json tests/perf/order-submission.js)
import http from "k6/http";
import { check, sleep } from "k6";
import { fetchToken } from "./lib/auth.js";

const ORDER_SERVICE_URL = __ENV.ORDER_SERVICE_URL || "http://localhost:8081";
const INVENTORY_SERVICE_URL = __ENV.INVENTORY_SERVICE_URL || "http://localhost:8082";
const OIDC_ISSUER_URI = __ENV.OIDC_ISSUER_URI || "http://localhost:8080/realms/fulfillops";
const OIDC_CLIENT_ID = __ENV.OIDC_CLI_CLIENT_ID || "fulfillops-cli";
const OIDC_CLIENT_SECRET = __ENV.OIDC_CLI_CLIENT_SECRET || "fulfillops-cli-local-secret";

export const options = {
  stages: [
    { duration: "5s", target: 10 },
    { duration: "20s", target: 10 },
    { duration: "5s", target: 0 },
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
  },
};

export function setup() {
  const adminToken = fetchToken(OIDC_ISSUER_URI, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, "admin.demo", "AdminDemo!123");
  const customerToken = fetchToken(OIDC_ISSUER_URI, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, "customer.demo", "CustomerDemo!123");
  const sku = `PERF-ORDER-SUBMISSION-${Date.now()}`;

  http.post(
    `${INVENTORY_SERVICE_URL}/api/v1/products`,
    JSON.stringify({ sku, name: "k6 order-submission widget", description: "tests/perf/order-submission.js" }),
    {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Idempotency-Key": `k6-create-${sku}`,
        "Content-Type": "application/json",
      },
    },
  );
  http.post(
    `${INVENTORY_SERVICE_URL}/api/v1/inventory/${sku}/adjustments`,
    JSON.stringify({ changeQuantity: 100000, reasonCode: "RESTOCK", reasonDetail: "k6 order-submission.js seed stock" }),
    {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Idempotency-Key": `k6-restock-${sku}`,
        "Content-Type": "application/json",
      },
    },
  );

  return { customerToken, sku };
}

export default function (data) {
  const idempotencyKey = `k6-order-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    items: [{ sku: data.sku, quantity: 1, unitPrice: { currencyCode: "USD", amount: "9.99" } }],
  });

  const response = http.post(`${ORDER_SERVICE_URL}/api/v1/orders`, body, {
    headers: {
      Authorization: `Bearer ${data.customerToken}`,
      "Idempotency-Key": idempotencyKey,
      "Content-Type": "application/json",
    },
  });

  check(response, {
    "order created (201)": (r) => r.status === 201,
  });

  sleep(0.1);
}
