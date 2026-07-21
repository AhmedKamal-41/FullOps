import { config } from "../config";
import { requestJson } from "./httpClient";
import type {
  DeadLetterEventResponse,
  FulfillmentHistoryEntryResponse,
  FulfillmentResponse,
  FulfillmentStatus,
  Page,
} from "./types";

const baseUrl = config.fulfillmentServiceUrl;

export function fetchFulfillments(
  accessToken: string,
  status: FulfillmentStatus,
  page = 0,
  size = 50,
) {
  return requestJson<Page<FulfillmentResponse>>(baseUrl, "/api/v1/fulfillments", {
    accessToken,
    query: { status, page, size },
  });
}

export function fetchFulfillment(accessToken: string, fulfillmentId: string) {
  return requestJson<FulfillmentResponse>(baseUrl, `/api/v1/fulfillments/${fulfillmentId}`, {
    accessToken,
  });
}

export function fetchFulfillmentHistory(accessToken: string, fulfillmentId: string) {
  return requestJson<FulfillmentHistoryEntryResponse[]>(
    baseUrl,
    `/api/v1/fulfillments/${fulfillmentId}/history`,
    { accessToken },
  );
}

export function claimFulfillment(accessToken: string, fulfillmentId: string, version: number) {
  return requestJson<FulfillmentResponse>(baseUrl, `/api/v1/fulfillments/${fulfillmentId}/claim`, {
    method: "POST",
    accessToken,
    ifMatch: version,
  });
}

export interface AdvanceFulfillmentRequest {
  newStatus: "PICKING" | "PACKED" | "DISPATCHED" | "DELIVERED";
  trackingReference?: string;
  deliveredAt?: string;
  notes?: string;
}

export function advanceFulfillment(
  accessToken: string,
  fulfillmentId: string,
  version: number,
  request: AdvanceFulfillmentRequest,
) {
  return requestJson<FulfillmentResponse>(baseUrl, `/api/v1/fulfillments/${fulfillmentId}/status`, {
    method: "PATCH",
    accessToken,
    ifMatch: version,
    body: request,
  });
}

export function cancelFulfillment(
  accessToken: string,
  fulfillmentId: string,
  version: number,
  reasonDetail: string,
) {
  return requestJson<FulfillmentResponse>(baseUrl, `/api/v1/fulfillments/${fulfillmentId}/cancel`, {
    method: "POST",
    accessToken,
    ifMatch: version,
    body: { reasonDetail },
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
