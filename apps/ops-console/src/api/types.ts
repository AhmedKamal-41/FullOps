// Every field here mirrors a backend DTO's exact JSON shape — not a client-convenience
// reshaping. In particular:
// `MoneyDto.amount` is always a decimal STRING, never coerce it with Number().

export interface MoneyDto {
  currencyCode: string;
  amount: string;
}

export interface Page<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export type OrderStatus =
  | "PENDING"
  | "INVENTORY_RESERVED"
  | "PAYMENT_AUTHORIZED"
  | "FULFILLMENT_ASSIGNED"
  | "PICKING"
  | "PACKED"
  | "DISPATCHED"
  | "DELIVERED"
  | "CANCELLATION_PENDING"
  | "CANCELLED"
  | "REQUIRES_REVIEW";

export type IncidentStatus = "OPEN" | "ACKNOWLEDGED" | "RESOLVED";

export type IncidentKind =
  "COMPENSATION_EXHAUSTED" | "CANCELLATION_AFTER_DISPATCH" | "CANCELLATION_STUCK";

export type FulfillmentStatus =
  "ASSIGNED" | "PICKING" | "PACKED" | "DISPATCHED" | "DELIVERED" | "CANCELLED";

export type DeadLetterEventStatus = "PENDING_REVIEW" | "REPLAYED";

export interface ReasonCodeCount {
  reasonCode: string;
  orderCount: number;
}

export interface KpiOverviewResponse {
  from: string;
  to: string;
  ordersReceived: number;
  ordersCompleted: number;
  ordersCancelled: number;
  inventoryRejections: number;
  inventoryRejectionRate: number;
  inventoryRejectionReasons: ReasonCodeCount[];
  paymentDeclines: number;
  paymentDeclineRate: number;
  paymentDeclineReasons: ReasonCodeCount[];
  paymentTechnicalFailureRate: number;
  fulfillmentThroughput: number;
  dltBacklogCount: number;
  oldestDltEventAt: string | null;
  outboxBacklogCount: number;
  oldestOutboxEventAt: string | null;
  recoverySuccessRate: number;
  manualTouchRate: number;
}

export interface TimeSeriesPoint {
  bucketStart: string;
  ordersReceived: number;
  ordersCompleted: number;
  ordersCancelled: number;
}

export interface KpiTimeSeriesResponse {
  from: string;
  to: string;
  interval: "HOUR" | "DAY";
  points: TimeSeriesPoint[];
}

export interface StagePercentiles {
  stage: string;
  p50Seconds: number | null;
  p90Seconds: number | null;
  p99Seconds: number | null;
  sampleCount: number;
}

export interface CycleTimePercentiles {
  p50Seconds: number | null;
  p90Seconds: number | null;
  p99Seconds: number | null;
  sampleCount: number;
}

export interface StageDurationKpiResponse {
  from: string;
  to: string;
  stages: StagePercentiles[];
  endToEndCycleTime: CycleTimePercentiles;
}

export interface StageBacklog {
  stage: string;
  openOrderCount: number;
  slaBreachedCount: number;
  slaBreachRate: number;
}

export interface BacklogResponse {
  stages: StageBacklog[];
}

export interface AgeBucket {
  label: string;
  orderCount: number;
}

export interface StuckOrdersResponse {
  totalStuckOrders: number;
  ageBuckets: AgeBucket[];
}

export interface LowStockSignalResponse {
  sku: string;
  availableQuantity: number;
  threshold: number;
  belowThreshold: boolean;
  occurredAt: string;
}

export interface WorkQueueItemResponse {
  orderId: string;
  customerId: string;
  status: OrderStatus;
  totalAmount: MoneyDto;
  createdAt: string;
  currentStageEnteredAt: string;
  updatedAt: string;
  inventoryRejectionReasonCode: string | null;
  paymentDeclineReasonCode: string | null;
  paymentTechnicalFailureCount: number;
  cancellationReasonCode: string | null;
  requiresReviewReasonCode: string | null;
  openIncidentCount: number;
}

export interface TimelineEntryResponse {
  type: "STATUS_CHANGE" | "INCIDENT_ACTION";
  occurredAt: string;
  status: OrderStatus | null;
  reasonCode: string | null;
  actor: string | null;
  detail: string | null;
}

export interface OrderTimelineResponse {
  orderId: string;
  entries: TimelineEntryResponse[];
}

export interface IncidentResponse {
  incidentId: string;
  orderId: string;
  kind: IncidentKind;
  detail: string;
  status: IncidentStatus;
  createdAt: string;
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  assignedTo: string | null;
  assignedAt: string | null;
  resolvedAt: string | null;
  resolvedBy: string | null;
  resolutionNote: string | null;
}

export interface DeadLetterEventResponse {
  eventId: string;
  consumerName: string;
  originalTopic: string;
  eventType: string;
  aggregateId: string;
  envelopeJson: string;
  status: DeadLetterEventStatus;
  createdAt: string;
  replayedAt: string | null;
  replayedBy: string | null;
}

export interface OrderItemResponse {
  sku: string;
  quantity: number;
  unitPrice: MoneyDto;
  lineTotal: MoneyDto;
}

export interface OrderResponse {
  orderId: string;
  customerId: string;
  status: OrderStatus;
  items: OrderItemResponse[];
  totalAmount: MoneyDto;
  createdAt: string;
  correlationId: string | null;
}

export interface FulfillmentResponse {
  fulfillmentId: string;
  orderId: string;
  status: FulfillmentStatus;
  warehouseId: string;
  assigneeId: string | null;
  slaDueAt: string;
  trackingReference: string | null;
  deliveredAt: string | null;
  cancellationReasonCode: string | null;
  cancellationReasonDetail: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface FulfillmentHistoryEntryResponse {
  status: FulfillmentStatus;
  actor: string;
  notes: string | null;
  occurredAt: string;
}
