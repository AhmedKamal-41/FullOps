import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useDemoMode } from "../demoMode/DemoModeContext";
import { useAccessToken } from "./useAccessToken";
import { queryKeys } from "./queryKeys";
import * as orderServiceApi from "./orderServiceApi";
import type { DateRange, IncidentFilters, WorkQueueFilters } from "./orderServiceApi";
import * as demo from "../demoData/fixtures";
import type { IncidentResponse } from "./types";

export function useOverviewKpis(range: DateRange) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.overview(range.from, range.to),
    queryFn: () =>
      isDemo
        ? Promise.resolve(demo.demoOverview)
        : orderServiceApi.fetchOverviewKpis(accessToken!, range),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useTimeseries(range: DateRange) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.timeseries(range.from, range.to),
    queryFn: () =>
      isDemo
        ? Promise.resolve(demo.demoTimeseries)
        : orderServiceApi.fetchTimeseries(accessToken!, range),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useStageDurations(range: DateRange) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.stageDurations(range.from, range.to),
    queryFn: () =>
      isDemo
        ? Promise.resolve(demo.demoStageDurations)
        : orderServiceApi.fetchStageDurations(accessToken!, range),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useBacklog() {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.backlog(),
    queryFn: () =>
      isDemo ? Promise.resolve(demo.demoBacklog) : orderServiceApi.fetchBacklog(accessToken!),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useStuckOrders() {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.stuckOrders(),
    queryFn: () =>
      isDemo
        ? Promise.resolve(demo.demoStuckOrders)
        : orderServiceApi.fetchStuckOrders(accessToken!),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useLowStock() {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.lowStock(),
    queryFn: () =>
      isDemo ? Promise.resolve(demo.demoLowStock) : orderServiceApi.fetchLowStock(accessToken!),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useWorkQueue(filters: WorkQueueFilters) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.workQueue(filters),
    queryFn: () =>
      isDemo
        ? Promise.resolve(demo.demoWorkQueue)
        : orderServiceApi.fetchWorkQueue(accessToken!, filters),
    enabled: isDemo || Boolean(accessToken),
    placeholderData: (previous) => previous,
  });
}

export function useOrder(orderId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.order(orderId),
    queryFn: () =>
      isDemo
        ? Promise.resolve({ ...demo.demoOrder, orderId })
        : orderServiceApi.fetchOrder(accessToken!, orderId),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useOrderTimeline(orderId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.orderTimeline(orderId),
    queryFn: () =>
      isDemo
        ? Promise.resolve({ ...demo.demoOrderTimeline, orderId })
        : orderServiceApi.fetchOrderTimeline(accessToken!, orderId),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useCancelOrder(orderId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reasonDetail: string) =>
      isDemo
        ? Promise.resolve({ ...demo.demoOrder, orderId, status: "CANCELLATION_PENDING" as const })
        : orderServiceApi.cancelOrder(accessToken!, orderId, crypto.randomUUID(), reasonDetail),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.order(orderId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.orderTimeline(orderId) });
    },
  });
}

export function useIncidents(filters: IncidentFilters) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.incidents(filters),
    queryFn: () =>
      isDemo
        ? Promise.resolve(filterDemoIncidents(filters))
        : orderServiceApi.fetchIncidents(accessToken!, filters),
    enabled: isDemo || Boolean(accessToken),
    placeholderData: (previous) => previous,
  });
}

function filterDemoIncidents(filters: IncidentFilters) {
  const content = demo.demoIncidents.content.filter((incident: IncidentResponse) => {
    if (filters.status && incident.status !== filters.status) return false;
    if (filters.kind && incident.kind !== filters.kind) return false;
    if (filters.orderId && incident.orderId !== filters.orderId) return false;
    return true;
  });
  return {
    content,
    page: { size: content.length || 20, number: 0, totalElements: content.length, totalPages: 1 },
  };
}

function useIncidentMutation(
  incidentId: string,
  demoResult: (current: IncidentResponse) => IncidentResponse,
  liveCall: (accessToken: string) => Promise<IncidentResponse>,
) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => {
      if (isDemo) {
        const current =
          demo.demoIncidents.content.find((i) => i.incidentId === incidentId) ??
          demo.demoIncidents.content[0];
        return Promise.resolve(demoResult(current));
      }
      return liveCall(accessToken!);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["incidents"] });
    },
  });
}

export function useAcknowledgeIncident(incidentId: string) {
  return useIncidentMutation(
    incidentId,
    (current) => ({
      ...current,
      status: "ACKNOWLEDGED",
      acknowledgedAt: new Date().toISOString(),
      acknowledgedBy: "demo-operator",
    }),
    (accessToken) => orderServiceApi.acknowledgeIncident(accessToken, incidentId),
  );
}

export function useAssignIncident(incidentId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (assignee: string) => {
      if (isDemo) {
        const current =
          demo.demoIncidents.content.find((i) => i.incidentId === incidentId) ??
          demo.demoIncidents.content[0];
        return Promise.resolve({
          ...current,
          assignedTo: assignee,
          assignedAt: new Date().toISOString(),
        });
      }
      return orderServiceApi.assignIncident(accessToken!, incidentId, assignee);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["incidents"] });
    },
  });
}

export function useResolveIncident(incidentId: string) {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (resolutionNote?: string) => {
      if (isDemo) {
        const current =
          demo.demoIncidents.content.find((i) => i.incidentId === incidentId) ??
          demo.demoIncidents.content[0];
        return Promise.resolve({
          ...current,
          status: "RESOLVED" as const,
          resolvedAt: new Date().toISOString(),
          resolvedBy: "demo-operator",
          resolutionNote: resolutionNote ?? null,
        });
      }
      return orderServiceApi.resolveIncident(accessToken!, incidentId, resolutionNote);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["incidents"] });
    },
  });
}

export function useDeadLetters() {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  return useQuery({
    queryKey: queryKeys.deadLetters(),
    queryFn: () =>
      isDemo
        ? Promise.resolve(demo.demoDeadLetters)
        : orderServiceApi.fetchDeadLetters(accessToken!),
    enabled: isDemo || Boolean(accessToken),
  });
}

export function useReplayDeadLetter() {
  const isDemo = useDemoMode();
  const accessToken = useAccessToken();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (eventId: string) => {
      const current = demo.demoDeadLetters.find((d) => d.eventId === eventId);
      return isDemo && current
        ? Promise.resolve({
            ...current,
            status: "REPLAYED" as const,
            replayedAt: new Date().toISOString(),
            replayedBy: "demo-operator",
          })
        : orderServiceApi.replayDeadLetter(accessToken!, eventId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.deadLetters() });
    },
  });
}

export function useWorkQueueExport() {
  const accessToken = useAccessToken();
  return (filters: Omit<WorkQueueFilters, "page" | "size" | "sort">) =>
    orderServiceApi.fetchWorkQueueExport(accessToken!, filters);
}
