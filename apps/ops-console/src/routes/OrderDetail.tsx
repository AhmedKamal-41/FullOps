import { Badge, Button, Card, Group, Stack, Table, Text, Title } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { useParams } from "react-router-dom";
import { PageHeading } from "../components/PageHeading";
import { StatusBadge } from "../components/StatusBadge";
import { ConfirmModal } from "../components/ConfirmModal";
import { EmptyState, ErrorState, LoadingState } from "../components/QueryState";
import { IncidentActions } from "../components/IncidentActions";
import { FulfillmentActions } from "../components/FulfillmentActions";
import { useCancelOrder, useIncidents, useOrder, useOrderTimeline } from "../api/hooks";
import { useFulfillmentForOrder } from "../api/fulfillmentHooks";
import { formatDateTime, formatDuration, formatMoney } from "../format";
import type { TimelineEntryResponse } from "../api/types";

const CANCELLABLE_STATUSES = new Set([
  "PENDING",
  "INVENTORY_RESERVED",
  "PAYMENT_AUTHORIZED",
  "FULFILLMENT_ASSIGNED",
  "PICKING",
  "PACKED",
  "DISPATCHED",
]);

interface StageDuration {
  status: string;
  enteredAt: string;
  durationSeconds: number | null;
}

// The backend has no "stage durations for this order" endpoint — this does the same
// consecutive-timestamp subtraction that order_stage_duration does server-side for the
// KPI aggregate, just for one order's own timeline entries.
function computeStageDurations(entries: TimelineEntryResponse[]): StageDuration[] {
  const statusChanges = entries.filter((entry) => entry.type === "STATUS_CHANGE" && entry.status);
  return statusChanges.map((entry, index) => {
    const next = statusChanges[index + 1];
    const durationSeconds = next
      ? (new Date(next.occurredAt).getTime() - new Date(entry.occurredAt).getTime()) / 1000
      : null;
    return { status: entry.status!, enteredAt: entry.occurredAt, durationSeconds };
  });
}

export function OrderDetail() {
  const { orderId = "" } = useParams();
  const order = useOrder(orderId);
  const timeline = useOrderTimeline(orderId);
  const incidents = useIncidents({ orderId });
  const [cancelOpened, cancelHandlers] = useDisclosure(false);
  const cancelOrder = useCancelOrder(orderId);

  const orderStatus = order.data?.status;
  const fulfillmentEnabled = Boolean(
    orderStatus && !["PENDING", "INVENTORY_RESERVED", "PAYMENT_AUTHORIZED"].includes(orderStatus),
  );
  const { fulfillment, isLoading: fulfillmentLoading } = useFulfillmentForOrder(
    orderId,
    fulfillmentEnabled,
  );

  if (order.isLoading) {
    return <LoadingState label="Loading order" />;
  }
  if (order.isError) {
    return <ErrorState error={order.error} />;
  }
  if (!order.data) {
    return <EmptyState message="Order not found." />;
  }

  const canCancel = CANCELLABLE_STATUSES.has(order.data.status);
  const stageDurations = timeline.data ? computeStageDurations(timeline.data.entries) : [];

  return (
    <Stack gap="lg">
      <PageHeading>{`Order ${order.data.orderId.slice(0, 8)}…`}</PageHeading>

      <Card withBorder padding="md">
        <Group justify="space-between" align="flex-start">
          <Stack gap={4}>
            <StatusBadge status={order.data.status} />
            <Text size="sm" c="dimmed">
              Created {formatDateTime(order.data.createdAt)}
            </Text>
            <Text size="sm" c="dimmed">
              Customer {order.data.customerId}
            </Text>
            <Text size="sm" c="dimmed">
              Correlation ID:{" "}
              {order.data.correlationId ?? "not recorded (order predates correlation tracking)"}
            </Text>
          </Stack>
          {canCancel && (
            <Button color="red" variant="light" onClick={cancelHandlers.open}>
              Cancel order
            </Button>
          )}
        </Group>

        <ConfirmModal
          opened={cancelOpened}
          onClose={cancelHandlers.close}
          title="Cancel order"
          description="This starts cancellation for the order. A reason is required."
          confirmLabel="Cancel order"
          requireReason
          reasonLabel="Reason"
          isSubmitting={cancelOrder.isPending}
          onConfirm={(reasonDetail) =>
            cancelOrder.mutate(reasonDetail, {
              onSuccess: cancelHandlers.close,
              onError: () =>
                notifications.show({
                  color: "red",
                  title: "Cancellation failed",
                  message: "Please try again.",
                }),
            })
          }
        />

        <Table mt="md">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>SKU</Table.Th>
              <Table.Th>Quantity</Table.Th>
              <Table.Th>Unit price</Table.Th>
              <Table.Th>Line total</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {order.data.items.map((item) => (
              <Table.Tr key={item.sku}>
                <Table.Td>{item.sku}</Table.Td>
                <Table.Td>{item.quantity}</Table.Td>
                <Table.Td>{formatMoney(item.unitPrice)}</Table.Td>
                <Table.Td>{formatMoney(item.lineTotal)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
          <Table.Tfoot>
            <Table.Tr>
              <Table.Th colSpan={3}>Total</Table.Th>
              <Table.Th>{formatMoney(order.data.totalAmount)}</Table.Th>
            </Table.Tr>
          </Table.Tfoot>
        </Table>
      </Card>

      {fulfillmentEnabled && (
        <Card withBorder padding="md">
          <Title order={2} size="h4" mb="sm">
            Fulfillment
          </Title>
          {fulfillmentLoading && <LoadingState label="Loading fulfillment" />}
          {!fulfillmentLoading && !fulfillment && (
            <EmptyState message="No open fulfillment found for this order." />
          )}
          {fulfillment && (
            <Stack gap="xs">
              <Group>
                <StatusBadge status={fulfillment.status} />
                <Text size="sm" c="dimmed">
                  Warehouse {fulfillment.warehouseId}
                </Text>
                {fulfillment.trackingReference && (
                  <Badge variant="outline">{fulfillment.trackingReference}</Badge>
                )}
              </Group>
              <FulfillmentActions fulfillment={fulfillment} />
            </Stack>
          )}
        </Card>
      )}

      <Card withBorder padding="md">
        <Title order={2} size="h4" mb="sm">
          Event timeline
        </Title>
        {timeline.isLoading && <LoadingState label="Loading timeline" />}
        {timeline.isError && <ErrorState error={timeline.error} />}
        {timeline.data && timeline.data.entries.length === 0 && (
          <EmptyState message="No events recorded yet." />
        )}
        {timeline.data && timeline.data.entries.length > 0 && (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>When</Table.Th>
                <Table.Th>Event</Table.Th>
                <Table.Th>Detail</Table.Th>
                <Table.Th>Stage duration</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {timeline.data.entries.map((entry, index) => {
                const stageDuration = stageDurations.find(
                  (stage) => stage.enteredAt === entry.occurredAt,
                );
                return (
                  <Table.Tr key={`${entry.occurredAt}-${index}`}>
                    <Table.Td>{formatDateTime(entry.occurredAt)}</Table.Td>
                    <Table.Td>
                      {entry.type === "STATUS_CHANGE" ? (
                        <StatusBadge status={entry.status ?? ""} />
                      ) : (
                        <Badge variant="light" color="gray">
                          Incident action
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td>{entry.detail ?? entry.reasonCode ?? entry.actor ?? "—"}</Table.Td>
                    <Table.Td>
                      {stageDuration ? formatDuration(stageDuration.durationSeconds) : "—"}
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        )}
      </Card>

      <Card withBorder padding="md">
        <Title order={2} size="h4" mb="sm">
          Incidents
        </Title>
        {incidents.isLoading && <LoadingState label="Loading incidents" />}
        {incidents.isError && <ErrorState error={incidents.error} />}
        {incidents.data && incidents.data.content.length === 0 && (
          <EmptyState message="No incidents for this order." />
        )}
        {incidents.data && incidents.data.content.length > 0 && (
          <Stack gap="sm">
            {incidents.data.content.map((incident) => (
              <Card key={incident.incidentId} withBorder padding="sm">
                <Group justify="space-between" align="flex-start">
                  <Stack gap={2}>
                    <Group gap="xs">
                      <StatusBadge status={incident.status} />
                      <Text fw={500}>{incident.kind}</Text>
                    </Group>
                    <Text size="sm" c="dimmed">
                      {incident.detail}
                    </Text>
                    <Text size="xs" c="dimmed">
                      Opened {formatDateTime(incident.createdAt)}
                    </Text>
                  </Stack>
                  <IncidentActions incident={incident} />
                </Group>
              </Card>
            ))}
          </Stack>
        )}
      </Card>
    </Stack>
  );
}
