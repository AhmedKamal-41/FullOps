import { config } from "../config";
import { requestBlob, requestJson } from "./httpClient";
import type {
  BacklogResponse,
  DeadLetterEventResponse,
  IncidentResponse,
  IncidentStatus,
  IncidentKind,
  KpiOverviewResponse,
  KpiTimeSeriesResponse,
  LowStockSignalResponse,
  OrderResponse,
  OrderTimelineResponse,
  Page,
  StageDurationKpiResponse,
  StuckOrdersResponse,
  WorkQueueItemResponse,
} from "./types";

const baseUrl = config.orderServiceUrl;

export interface DateRange {
  from: string;
  to: string;
  [key: string]: string | number | boolean | undefined;
}

export function fetchOverviewKpis(accessToken: string, range: DateRange) {
  return requestJson<KpiOverviewResponse>(baseUrl, "/api/v1/ops/kpis/overview", {
    accessToken,
    query: range,
  });
}

export function fetchTimeseries(
  accessToken: string,
  range: DateRange,
  interval: "HOUR" | "DAY" = "DAY",
) {
  return requestJson<KpiTimeSeriesResponse>(baseUrl, "/api/v1/ops/kpis/timeseries", {
    accessToken,
    query: { ...range, interval },
  });
}

export function fetchStageDurations(accessToken: string, range: DateRange) {
  return requestJson<StageDurationKpiResponse>(baseUrl, "/api/v1/ops/kpis/stage-durations", {
    accessToken,
    query: range,
  });
}

export function fetchBacklog(accessToken: string) {
  return requestJson<BacklogResponse>(baseUrl, "/api/v1/ops/backlog", { accessToken });
}

export function fetchStuckOrders(accessToken: string) {
  return requestJson<StuckOrdersResponse>(baseUrl, "/api/v1/ops/kpis/stuck-orders", {
    accessToken,
  });
}

export function fetchLowStock(accessToken: string) {
  return requestJson<LowStockSignalResponse[]>(baseUrl, "/api/v1/ops/low-stock", {
    accessToken,
  });
}

export interface WorkQueueFilters {
  status?: string;
  customerId?: string;
  slaBreached?: boolean;
  stuck?: boolean;
  page?: number;
  size?: number;
  sort?: string;
  [key: string]: string | number | boolean | undefined;
}

export function fetchWorkQueue(accessToken: string, filters: WorkQueueFilters) {
  return requestJson<Page<WorkQueueItemResponse>>(baseUrl, "/api/v1/ops/work-queue", {
    accessToken,
    query: filters,
  });
}

export function fetchWorkQueueExport(
  accessToken: string,
  filters: Omit<WorkQueueFilters, "page" | "size" | "sort">,
) {
  return requestBlob(baseUrl, "/api/v1/ops/work-queue/export", {
    accessToken,
    query: filters,
  });
}

export function fetchOrderTimeline(accessToken: string, orderId: string) {
  return requestJson<OrderTimelineResponse>(baseUrl, `/api/v1/ops/orders/${orderId}/timeline`, {
    accessToken,
  });
}

export function fetchOrder(accessToken: string, orderId: string) {
  return requestJson<OrderResponse>(baseUrl, `/api/v1/orders/${orderId}`, { accessToken });
}

export function cancelOrder(
  accessToken: string,
  orderId: string,
  idempotencyKey: string,
  reasonDetail: string,
) {
  return requestJson<OrderResponse>(baseUrl, `/api/v1/orders/${orderId}/cancellation-requests`, {
    method: "POST",
    accessToken,
    idempotencyKey,
    body: { reasonDetail },
  });
}

export interface IncidentFilters {
  status?: IncidentStatus;
  kind?: IncidentKind;
  orderId?: string;
  page?: number;
  size?: number;
  sort?: string;
  [key: string]: string | number | boolean | undefined;
}

export function fetchIncidents(accessToken: string, filters: IncidentFilters) {
  return requestJson<Page<IncidentResponse>>(baseUrl, "/api/v1/ops/incidents", {
    accessToken,
    query: filters,
  });
}

export function acknowledgeIncident(accessToken: string, incidentId: string) {
  return requestJson<IncidentResponse>(baseUrl, `/api/v1/ops/incidents/${incidentId}/acknowledge`, {
    method: "POST",
    accessToken,
  });
}

export function assignIncident(accessToken: string, incidentId: string, assignee: string) {
  return requestJson<IncidentResponse>(baseUrl, `/api/v1/ops/incidents/${incidentId}/assign`, {
    method: "POST",
    accessToken,
    body: { assignee },
  });
}

export function resolveIncident(accessToken: string, incidentId: string, resolutionNote?: string) {
  return requestJson<IncidentResponse>(baseUrl, `/api/v1/ops/incidents/${incidentId}/resolve`, {
    method: "POST",
    accessToken,
    body: resolutionNote ? { resolutionNote } : undefined,
  });
}

export function fetchDeadLetters(accessToken: string) {
  return requestJson<DeadLetterEventResponse[]>(baseUrl, "/api/v1/admin/dead-letters", {
    accessToken,
  });
}

export function replayDeadLetter(accessToken: string, eventId: string) {
  return requestJson<DeadLetterEventResponse>(
    baseUrl,
    `/api/v1/admin/dead-letters/${eventId}/replay`,
    { method: "POST", accessToken },
  );
}
