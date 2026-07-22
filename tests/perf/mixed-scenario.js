// Objective (defined before running — no number below this file is real until a run of this
// exact script produces it):
//   Combine both read and write load against the same local stack at once — 8 virtual users
//   continuously placing orders and 8 virtual users continuously querying the ops work-queue,
//   both for 30s — to see whether the read path degrades while the write path is busy (and vice
//   versa) on a single-node local Compose stack. Target: p95 under 700ms and an HTTP error rate
//   under 1% for both scenarios combined. Not a production capacity claim.
//
// Prerequisites: `make infra-up`, all four services running, and at least one fictional product
// (setup() below creates and stocks its own).
//
// Run: k6 run --summary-export=docs/evidence/k6/mixed-scenario-summary.json tests/perf/mixed-scenario.js
import http from "k6/http";
import { check, sleep } from "k6";
import { fetchToken } from "./lib/auth.js";

const ORDER_SERVICE_URL = __ENV.ORDER_SERVICE_URL || "http://localhost:8081";
const INVENTORY_SERVICE_URL = __ENV.INVENTORY_SERVICE_URL || "http://localhost:8082";
const OIDC_ISSUER_URI = __ENV.OIDC_ISSUER_URI || "http://localhost:8080/realms/fulfillops";
const OIDC_CLIENT_ID = __ENV.OIDC_CLI_CLIENT_ID || "fulfillops-cli";
const OIDC_CLIENT_SECRET = __ENV.OIDC_CLI_CLIENT_SECRET || "fulfillops-cli-local-secret";

export const options = {
  scenarios: {
    place_orders: {
      executor: "constant-vus",
      vus: 8,
      duration: "30s",
      exec: "placeOrders",
    },
    query_work_queue: {
      executor: "constant-vus",
      vus: 8,
      duration: "30s",
      exec: "queryWorkQueue",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<700"],
  },
};

export function setup() {
  const adminToken = fetchToken(OIDC_ISSUER_URI, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, "admin.demo", "AdminDemo!123");
  const customerToken = fetchToken(OIDC_ISSUER_URI, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, "customer.demo", "CustomerDemo!123");
  const sku = `PERF-MIXED-${Date.now()}`;

  http.post(
    `${INVENTORY_SERVICE_URL}/api/v1/products`,
    JSON.stringify({ sku, name: "k6 mixed-scenario widget", description: "tests/perf/mixed-scenario.js" }),
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
    JSON.stringify({ changeQuantity: 100000, reasonCode: "RESTOCK", reasonDetail: "k6 mixed-scenario.js seed stock" }),
    {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Idempotency-Key": `k6-restock-${sku}`,
        "Content-Type": "application/json",
      },
    },
  );

  return { adminToken, customerToken, sku };
}

export function placeOrders(data) {
  const idempotencyKey = `k6-mixed-order-${__VU}-${__ITER}-${Date.now()}`;
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

  check(response, { "order created (201)": (r) => r.status === 201 });
  sleep(0.1);
}

export function queryWorkQueue(data) {
  const response = http.get(`${ORDER_SERVICE_URL}/api/v1/ops/work-queue?page=0&size=20`, {
    headers: { Authorization: `Bearer ${data.adminToken}` },
  });

  check(response, { "work queue returned (200)": (r) => r.status === 200 });
  sleep(0.2);
}
