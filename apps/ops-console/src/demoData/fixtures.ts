// Static, fictional example data — the ONLY place mock data is allowed to originate (see
// DemoModeContext). Shaped identically to the real backend DTOs. No real names, emails,
// or tokens anywhere in this file.
import type {
  BacklogResponse,
  DeadLetterEventResponse,
  FulfillmentHistoryEntryResponse,
  FulfillmentResponse,
  IncidentResponse,
  KpiOverviewResponse,
  KpiTimeSeriesResponse,
  LowStockSignalResponse,
  OrderResponse,
  OrderTimelineResponse,
  Page,
  StageDurationKpiResponse,
  StuckOrdersResponse,
  WorkQueueItemResponse,
} from "../api/types";

function page<T>(content: T[]): Page<T> {
  return {
    content,
    page: { size: content.length || 20, number: 0, totalElements: content.length, totalPages: 1 },
  };
}

export const demoOverview: KpiOverviewResponse = {
  from: "2026-07-14T00:00:00Z",
  to: "2026-07-21T00:00:00Z",
  ordersReceived: 482,
  ordersCompleted: 401,
  ordersCancelled: 23,
  inventoryRejections: 14,
  inventoryRejectionRate: 0.029,
  inventoryRejectionReasons: [
    { reasonCode: "OUT_OF_STOCK", orderCount: 9 },
    { reasonCode: "RESERVATION_CONFLICT", orderCount: 5 },
  ],
  paymentDeclines: 19,
  paymentDeclineRate: 0.039,
  paymentDeclineReasons: [
    { reasonCode: "INSUFFICIENT_FUNDS", orderCount: 11 },
    { reasonCode: "CARD_EXPIRED", orderCount: 8 },
  ],
  paymentTechnicalFailureRate: 0.006,
  fulfillmentThroughput: 401,
  dltBacklogCount: 2,
  oldestDltEventAt: "2026-07-19T08:12:00Z",
  outboxBacklogCount: 0,
  oldestOutboxEventAt: null,
  recoverySuccessRate: 0.87,
  manualTouchRate: 0.08,
};

export const demoTimeseries: KpiTimeSeriesResponse = {
  from: demoOverview.from,
  to: demoOverview.to,
  interval: "DAY",
  points: [
    {
      bucketStart: "2026-07-14T00:00:00Z",
      ordersReceived: 61,
      ordersCompleted: 55,
      ordersCancelled: 3,
    },
    {
      bucketStart: "2026-07-15T00:00:00Z",
      ordersReceived: 70,
      ordersCompleted: 58,
      ordersCancelled: 4,
    },
    {
      bucketStart: "2026-07-16T00:00:00Z",
      ordersReceived: 64,
      ordersCompleted: 57,
      ordersCancelled: 2,
    },
    {
      bucketStart: "2026-07-17T00:00:00Z",
      ordersReceived: 73,
      ordersCompleted: 60,
      ordersCancelled: 5,
    },
    {
      bucketStart: "2026-07-18T00:00:00Z",
      ordersReceived: 68,
      ordersCompleted: 59,
      ordersCancelled: 3,
    },
    {
      bucketStart: "2026-07-19T00:00:00Z",
      ordersReceived: 75,
      ordersCompleted: 62,
      ordersCancelled: 4,
    },
    {
      bucketStart: "2026-07-20T00:00:00Z",
      ordersReceived: 71,
      ordersCompleted: 50,
      ordersCancelled: 2,
    },
  ],
};

export const demoStageDurations: StageDurationKpiResponse = {
  from: demoOverview.from,
  to: demoOverview.to,
  stages: [
    {
      stage: "INVENTORY_RESERVED",
      p50Seconds: 4,
      p90Seconds: 12,
      p99Seconds: 40,
      sampleCount: 470,
    },
    {
      stage: "PAYMENT_AUTHORIZED",
      p50Seconds: 6,
      p90Seconds: 18,
      p99Seconds: 55,
      sampleCount: 465,
    },
    {
      stage: "FULFILLMENT_ASSIGNED",
      p50Seconds: 900,
      p90Seconds: 3600,
      p99Seconds: 9000,
      sampleCount: 440,
    },
    { stage: "PICKING", p50Seconds: 1800, p90Seconds: 5400, p99Seconds: 12000, sampleCount: 430 },
    { stage: "PACKED", p50Seconds: 1200, p90Seconds: 3000, p99Seconds: 7000, sampleCount: 425 },
    {
      stage: "DISPATCHED",
      p50Seconds: 86400,
      p90Seconds: 172800,
      p99Seconds: 259200,
      sampleCount: 401,
    },
  ],
  endToEndCycleTime: {
    p50Seconds: 95000,
    p90Seconds: 190000,
    p99Seconds: 280000,
    sampleCount: 401,
  },
};

export const demoBacklog: BacklogResponse = {
  stages: [
    { stage: "INVENTORY_RESERVED", openOrderCount: 3, slaBreachedCount: 0, slaBreachRate: 0 },
    { stage: "FULFILLMENT_ASSIGNED", openOrderCount: 11, slaBreachedCount: 1, slaBreachRate: 0.09 },
    { stage: "PICKING", openOrderCount: 8, slaBreachedCount: 2, slaBreachRate: 0.25 },
    { stage: "PACKED", openOrderCount: 5, slaBreachedCount: 0, slaBreachRate: 0 },
    { stage: "DISPATCHED", openOrderCount: 22, slaBreachedCount: 3, slaBreachRate: 0.14 },
  ],
};

export const demoStuckOrders: StuckOrdersResponse = {
  totalStuckOrders: 6,
  ageBuckets: [
    { label: "1-2 days", orderCount: 3 },
    { label: "3-5 days", orderCount: 2 },
    { label: "6+ days", orderCount: 1 },
  ],
};

export const demoLowStock: LowStockSignalResponse[] = [
  {
    sku: "SKU-DEMO-CABLE-2M",
    availableQuantity: 4,
    threshold: 20,
    belowThreshold: true,
    occurredAt: "2026-07-20T09:30:00Z",
  },
  {
    sku: "SKU-DEMO-CASE-BLK",
    availableQuantity: 12,
    threshold: 25,
    belowThreshold: true,
    occurredAt: "2026-07-20T11:15:00Z",
  },
  {
    sku: "SKU-DEMO-STAND-AL",
    availableQuantity: 0,
    threshold: 10,
    belowThreshold: true,
    occurredAt: "2026-07-19T16:40:00Z",
  },
];

// Distinct leading octets on purpose: the console truncates order IDs to their first 8
// hex characters for display (Work Queue, Incidents, Fulfillment Board), so demo orders
// need visibly distinct prefixes the same way real random UUIDs would.
const demoOrderId1 = "a1111111-1111-4111-8111-111111111101";
const demoOrderId2 = "b2222222-1111-4111-8111-111111111102";
const demoOrderId3 = "c3333333-1111-4111-8111-111111111103";
const demoCustomerId = "22222222-2222-4222-8222-222222222201";

export const demoWorkQueue: Page<WorkQueueItemResponse> = page([
  {
    orderId: demoOrderId1,
    customerId: demoCustomerId,
    status: "DISPATCHED",
    totalAmount: { currencyCode: "USD", amount: "84.50" },
    createdAt: "2026-07-18T10:00:00Z",
    currentStageEnteredAt: "2026-07-19T14:00:00Z",
    updatedAt: "2026-07-19T14:00:00Z",
    inventoryRejectionReasonCode: null,
    paymentDeclineReasonCode: null,
    paymentTechnicalFailureCount: 0,
    cancellationReasonCode: null,
    requiresReviewReasonCode: null,
    openIncidentCount: 0,
  },
  {
    orderId: demoOrderId2,
    customerId: demoCustomerId,
    status: "REQUIRES_REVIEW",
    totalAmount: { currencyCode: "USD", amount: "129.99" },
    createdAt: "2026-07-17T08:30:00Z",
    currentStageEnteredAt: "2026-07-19T09:00:00Z",
    updatedAt: "2026-07-19T09:00:00Z",
    inventoryRejectionReasonCode: null,
    paymentDeclineReasonCode: null,
    paymentTechnicalFailureCount: 0,
    cancellationReasonCode: null,
    requiresReviewReasonCode: "CANCELLATION_STUCK",
    openIncidentCount: 1,
  },
  {
    orderId: demoOrderId3,
    customerId: demoCustomerId,
    status: "CANCELLED",
    totalAmount: { currencyCode: "USD", amount: "39.00" },
    createdAt: "2026-07-16T12:00:00Z",
    currentStageEnteredAt: "2026-07-16T12:05:00Z",
    updatedAt: "2026-07-16T12:05:00Z",
    inventoryRejectionReasonCode: "OUT_OF_STOCK",
    paymentDeclineReasonCode: null,
    paymentTechnicalFailureCount: 0,
    cancellationReasonCode: "INVENTORY_UNAVAILABLE",
    requiresReviewReasonCode: null,
    openIncidentCount: 0,
  },
]);

export const demoOrder: OrderResponse = {
  orderId: demoOrderId1,
  customerId: demoCustomerId,
  status: "DISPATCHED",
  items: [
    {
      sku: "SKU-DEMO-CABLE-2M",
      quantity: 2,
      unitPrice: { currencyCode: "USD", amount: "12.25" },
      lineTotal: { currencyCode: "USD", amount: "24.50" },
    },
    {
      sku: "SKU-DEMO-CASE-BLK",
      quantity: 1,
      unitPrice: { currencyCode: "USD", amount: "60.00" },
      lineTotal: { currencyCode: "USD", amount: "60.00" },
    },
  ],
  totalAmount: { currencyCode: "USD", amount: "84.50" },
  createdAt: "2026-07-18T10:00:00Z",
  correlationId: "33333333-3333-4333-8333-333333333301",
};

export const demoOrderTimeline: OrderTimelineResponse = {
  orderId: demoOrderId1,
  entries: [
    {
      type: "STATUS_CHANGE",
      occurredAt: "2026-07-18T10:00:00Z",
      status: "PENDING",
      reasonCode: null,
      actor: null,
      detail: null,
    },
    {
      type: "STATUS_CHANGE",
      occurredAt: "2026-07-18T10:00:04Z",
      status: "INVENTORY_RESERVED",
      reasonCode: null,
      actor: null,
      detail: null,
    },
    {
      type: "STATUS_CHANGE",
      occurredAt: "2026-07-18T10:00:09Z",
      status: "PAYMENT_AUTHORIZED",
      reasonCode: null,
      actor: null,
      detail: null,
    },
    {
      type: "STATUS_CHANGE",
      occurredAt: "2026-07-18T10:15:00Z",
      status: "FULFILLMENT_ASSIGNED",
      reasonCode: null,
      actor: null,
      detail: null,
    },
    {
      type: "STATUS_CHANGE",
      occurredAt: "2026-07-18T11:00:00Z",
      status: "PICKING",
      reasonCode: null,
      actor: null,
      detail: null,
    },
    {
      type: "STATUS_CHANGE",
      occurredAt: "2026-07-18T11:30:00Z",
      status: "PACKED",
      reasonCode: null,
      actor: null,
      detail: null,
    },
    {
      type: "STATUS_CHANGE",
      occurredAt: "2026-07-19T14:00:00Z",
      status: "DISPATCHED",
      reasonCode: null,
      actor: null,
      detail: null,
    },
  ],
};

export const demoIncidents: Page<IncidentResponse> = page([
  {
    incidentId: "44444444-4444-4444-8444-444444444401",
    orderId: demoOrderId2,
    kind: "CANCELLATION_STUCK",
    detail: "Automatic cancellation retry did not resolve within the compensation window.",
    status: "OPEN",
    createdAt: "2026-07-19T09:00:00Z",
    acknowledgedAt: null,
    acknowledgedBy: null,
    assignedTo: null,
    assignedAt: null,
    resolvedAt: null,
    resolvedBy: null,
    resolutionNote: null,
  },
]);

export const demoFulfillmentsByStatus: Record<string, Page<FulfillmentResponse>> = {
  ASSIGNED: page([
    {
      fulfillmentId: "55555555-5555-4555-8555-555555555501",
      orderId: "d4444444-1111-4111-8111-111111111104",
      status: "ASSIGNED",
      warehouseId: "WH-DEMO-1",
      assigneeId: null,
      slaDueAt: "2026-07-21T18:00:00Z",
      trackingReference: null,
      deliveredAt: null,
      cancellationReasonCode: null,
      cancellationReasonDetail: null,
      version: 0,
      createdAt: "2026-07-20T15:00:00Z",
      updatedAt: "2026-07-20T15:00:00Z",
    },
  ]),
  PICKING: page([]),
  PACKED: page([]),
  DISPATCHED: page([
    {
      fulfillmentId: "55555555-5555-4555-8555-555555555502",
      orderId: demoOrderId1,
      status: "DISPATCHED",
      warehouseId: "WH-DEMO-1",
      assigneeId: "demo-picker-1",
      slaDueAt: "2026-07-21T14:00:00Z",
      trackingReference: "DEMO-TRACK-0001",
      deliveredAt: null,
      cancellationReasonCode: null,
      cancellationReasonDetail: null,
      version: 3,
      createdAt: "2026-07-18T11:00:00Z",
      updatedAt: "2026-07-19T14:00:00Z",
    },
  ]),
};

export const demoFulfillmentHistory: FulfillmentHistoryEntryResponse[] = [
  { status: "ASSIGNED", actor: "system", notes: null, occurredAt: "2026-07-18T10:15:00Z" },
  { status: "PICKING", actor: "demo-picker-1", notes: null, occurredAt: "2026-07-18T11:00:00Z" },
  { status: "PACKED", actor: "demo-picker-1", notes: null, occurredAt: "2026-07-18T11:30:00Z" },
  {
    status: "DISPATCHED",
    actor: "demo-picker-1",
    notes: "left with carrier",
    occurredAt: "2026-07-19T14:00:00Z",
  },
];

export const demoDeadLetters: DeadLetterEventResponse[] = [
  {
    eventId: "66666666-6666-4666-8666-666666666601",
    consumerName: "fulfillment-events-listener",
    originalTopic: "fulfillops.fulfillment.events",
    eventType: "FulfillmentStatusChanged",
    aggregateId: demoOrderId2,
    envelopeJson: '{"eventType":"FulfillmentStatusChanged","note":"example only"}',
    status: "PENDING_REVIEW",
    createdAt: "2026-07-19T08:12:00Z",
    replayedAt: null,
    replayedBy: null,
  },
];
