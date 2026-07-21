import { http, HttpResponse } from "msw";
import { config } from "../config";
import * as demo from "../demoData/fixtures";

const orderBase = config.orderServiceUrl;
const fulfillmentBase = config.fulfillmentServiceUrl;

export const handlers = [
  http.get(`${orderBase}/api/v1/ops/kpis/overview`, () => HttpResponse.json(demo.demoOverview)),
  http.get(`${orderBase}/api/v1/ops/kpis/timeseries`, () => HttpResponse.json(demo.demoTimeseries)),
  http.get(`${orderBase}/api/v1/ops/kpis/stage-durations`, () =>
    HttpResponse.json(demo.demoStageDurations),
  ),
  http.get(`${orderBase}/api/v1/ops/backlog`, () => HttpResponse.json(demo.demoBacklog)),
  http.get(`${orderBase}/api/v1/ops/kpis/stuck-orders`, () =>
    HttpResponse.json(demo.demoStuckOrders),
  ),
  http.get(`${orderBase}/api/v1/ops/low-stock`, () => HttpResponse.json(demo.demoLowStock)),
  http.get(`${orderBase}/api/v1/ops/work-queue`, () => HttpResponse.json(demo.demoWorkQueue)),
  http.get(`${orderBase}/api/v1/ops/orders/:orderId/timeline`, () =>
    HttpResponse.json(demo.demoOrderTimeline),
  ),
  http.get(`${orderBase}/api/v1/orders/:orderId`, ({ params }) =>
    HttpResponse.json({ ...demo.demoOrder, orderId: params.orderId }),
  ),
  http.post(`${orderBase}/api/v1/orders/:orderId/cancellation-requests`, () =>
    HttpResponse.json({ ...demo.demoOrder, status: "CANCELLATION_PENDING" }),
  ),
  http.get(`${orderBase}/api/v1/ops/incidents`, () => HttpResponse.json(demo.demoIncidents)),
  http.post(`${orderBase}/api/v1/ops/incidents/:incidentId/acknowledge`, () =>
    HttpResponse.json({
      ...demo.demoIncidents.content[0],
      status: "ACKNOWLEDGED",
      acknowledgedAt: new Date().toISOString(),
      acknowledgedBy: "test-operator",
    }),
  ),
  http.post(`${orderBase}/api/v1/ops/incidents/:incidentId/assign`, () =>
    HttpResponse.json({ ...demo.demoIncidents.content[0], assignedTo: "test-operator" }),
  ),
  http.post(`${orderBase}/api/v1/ops/incidents/:incidentId/resolve`, () =>
    HttpResponse.json({ ...demo.demoIncidents.content[0], status: "RESOLVED" }),
  ),
  http.get(`${orderBase}/api/v1/admin/dead-letters`, () => HttpResponse.json(demo.demoDeadLetters)),
  http.post(`${orderBase}/api/v1/admin/dead-letters/:eventId/replay`, () =>
    HttpResponse.json({ ...demo.demoDeadLetters[0], status: "REPLAYED" }),
  ),

  http.get(`${fulfillmentBase}/api/v1/fulfillments`, ({ request }) => {
    const status = new URL(request.url).searchParams.get("status") ?? "ASSIGNED";
    return HttpResponse.json(
      demo.demoFulfillmentsByStatus[status] ?? {
        content: [],
        page: { size: 0, number: 0, totalElements: 0, totalPages: 0 },
      },
    );
  }),
  http.get(`${fulfillmentBase}/api/v1/fulfillments/:fulfillmentId/history`, () =>
    HttpResponse.json(demo.demoFulfillmentHistory),
  ),
];
