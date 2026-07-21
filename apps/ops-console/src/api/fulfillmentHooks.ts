import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useDemoMode } from "../demoMode/DemoModeContext";
import { useAccessToken } from "./useAccessToken";
import { queryKeys } from "./queryKeys";
import * as fulfillmentServiceApi from "./fulfillmentServiceApi";
import type { AdvanceFulfillmentRequest } from "./fulfillmentServiceApi";
import * as demo from "../demoData/fixtures";
import type { FulfillmentStatus } from "./types";

export function useFulfillments(status: FulfillmentStatus, enabled = true) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.fulfillments(status),
    queryFn: () =>
      isDemo
        ? Promise.resolve(
            demo.demoFulfillmentsByStatus[status] ?? {
              content: [],
              page: { size: 0, number: 0, totalElements: 0, totalPages: 0 },
            },
          )
        : fulfillmentServiceApi.fetchFulfillments(accessToken!, status),
    enabled: enabled && (isDemo || Boolean(accessToken)),
  });
}

// The order-service and fulfillment-service APIs have no "find fulfillment by order"
// lookup (see the Phase 10 plan's three scoped backend corrections — this wasn't one of
// them). Order Detail finds its order's fulfillment by checking each open fulfillment
// status's list, which is small in this system's operational scale.
const OPEN_FULFILLMENT_STATUSES: FulfillmentStatus[] = [
  "ASSIGNED",
  "PICKING",
  "PACKED",
  "DISPATCHED",
];

export function useFulfillmentForOrder(orderId: string, enabled: boolean) {
  const assigned = useFulfillments("ASSIGNED", enabled);
  const picking = useFulfillments("PICKING", enabled);
  const packed = useFulfillments("PACKED", enabled);
  const dispatched = useFulfillments("DISPATCHED", enabled);

  const queries = [assigned, picking, packed, dispatched];
  const isLoading = enabled && queries.some((q) => q.isLoading);
  const isError = queries.some((q) => q.isError);
  const error = queries.find((q) => q.isError)?.error;

  const fulfillment = OPEN_FULFILLMENT_STATUSES.map((_status, index) =>
    queries[index].data?.content.find((f) => f.orderId === orderId),
  ).find((match) => match !== undefined);

  return { fulfillment, isLoading, isError, error };
}

export function useFulfillmentHistory(fulfillmentId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.fulfillmentHistory(fulfillmentId),
    queryFn: () =>
      isDemo
        ? Promise.resolve(demo.demoFulfillmentHistory)
        : fulfillmentServiceApi.fetchFulfillmentHistory(accessToken!, fulfillmentId),
    enabled: isDemo || Boolean(accessToken),
  });
}

function useInvalidateFulfillments() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: ["fulfillments"] });
}

export function useClaimFulfillment(fulfillmentId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  const invalidate = useInvalidateFulfillments();
  return useMutation({
    mutationFn: (version: number) =>
      isDemo
        ? Promise.resolve({
            ...findDemoFulfillment(fulfillmentId),
            assigneeId: "demo-operator",
            version: version + 1,
          })
        : fulfillmentServiceApi.claimFulfillment(accessToken!, fulfillmentId, version),
    onSuccess: invalidate,
  });
}

export function useAdvanceFulfillment(fulfillmentId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  const invalidate = useInvalidateFulfillments();
  return useMutation({
    mutationFn: ({ version, request }: { version: number; request: AdvanceFulfillmentRequest }) =>
      isDemo
        ? Promise.resolve({
            ...findDemoFulfillment(fulfillmentId),
            status: request.newStatus,
            trackingReference: request.trackingReference ?? null,
            deliveredAt: request.deliveredAt ?? null,
            version: version + 1,
          })
        : fulfillmentServiceApi.advanceFulfillment(accessToken!, fulfillmentId, version, request),
    onSuccess: invalidate,
  });
}

export function useCancelFulfillment(fulfillmentId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  const invalidate = useInvalidateFulfillments();
  return useMutation({
    mutationFn: ({ version, reasonDetail }: { version: number; reasonDetail: string }) =>
      isDemo
        ? Promise.resolve({
            ...findDemoFulfillment(fulfillmentId),
            status: "CANCELLED" as const,
            cancellationReasonDetail: reasonDetail,
            version: version + 1,
          })
        : fulfillmentServiceApi.cancelFulfillment(
            accessToken!,
            fulfillmentId,
            version,
            reasonDetail,
          ),
    onSuccess: invalidate,
  });
}

function findDemoFulfillment(fulfillmentId: string) {
  for (const p of Object.values(demo.demoFulfillmentsByStatus)) {
    const match = p.content.find((f) => f.fulfillmentId === fulfillmentId);
    if (match) return match;
  }
  return demo.demoFulfillmentsByStatus.ASSIGNED.content[0];
}
