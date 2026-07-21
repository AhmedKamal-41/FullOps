import { useMemo } from "react";
import {
  Badge,
  Button,
  Card,
  Group,
  Pagination,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
} from "@mantine/core";
import { IconDownload } from "@tabler/icons-react";
import { Link, useSearchParams } from "react-router-dom";
import { notifications } from "@mantine/notifications";
import { PageHeading } from "../components/PageHeading";
import { StatusBadge } from "../components/StatusBadge";
import { EmptyState, ErrorState, LoadingState } from "../components/QueryState";
import { useWorkQueue, useWorkQueueExport } from "../api/hooks";
import { formatDateTime, formatMoney } from "../format";

const ORDER_STATUSES = [
  "PENDING",
  "INVENTORY_RESERVED",
  "PAYMENT_AUTHORIZED",
  "FULFILLMENT_ASSIGNED",
  "PICKING",
  "PACKED",
  "DISPATCHED",
  "DELIVERED",
  "CANCELLATION_PENDING",
  "CANCELLED",
  "REQUIRES_REVIEW",
];

const PAGE_SIZE = 20;

function ageSince(iso: string): string {
  const elapsedMs = Date.now() - new Date(iso).getTime();
  const elapsedSeconds = Math.max(0, elapsedMs / 1000);
  if (elapsedSeconds < 3600) return `${Math.round(elapsedSeconds / 60)}m`;
  if (elapsedSeconds < 86400) return `${(elapsedSeconds / 3600).toFixed(1)}h`;
  return `${(elapsedSeconds / 86400).toFixed(1)}d`;
}

export function WorkQueue() {
  const [searchParams, setSearchParams] = useSearchParams();
  const exportCsv = useWorkQueueExport();

  const status = searchParams.get("status") ?? "";
  const customerId = searchParams.get("customerId") ?? "";
  const slaBreached = searchParams.get("slaBreached") ?? "";
  const stuck = searchParams.get("stuck") ?? "";
  const sort = searchParams.get("sort") ?? "createdAt,desc";
  const page = Number(searchParams.get("page") ?? "0");

  const filters = useMemo(
    () => ({
      status: status || undefined,
      customerId: customerId || undefined,
      slaBreached: slaBreached ? slaBreached === "true" : undefined,
      stuck: stuck ? stuck === "true" : undefined,
      sort,
      page,
      size: PAGE_SIZE,
    }),
    [status, customerId, slaBreached, stuck, sort, page],
  );

  const workQueue = useWorkQueue(filters);

  function updateParam(key: string, value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) {
      next.set(key, value);
    } else {
      next.delete(key);
    }
    next.delete("page");
    setSearchParams(next);
  }

  function setPage(nextPage: number) {
    const next = new URLSearchParams(searchParams);
    next.set("page", String(nextPage));
    setSearchParams(next);
  }

  function toggleSort(field: string) {
    const [currentField, currentDirection] = sort.split(",");
    const nextDirection = currentField === field && currentDirection === "asc" ? "desc" : "asc";
    updateParam("sort", `${field},${nextDirection}`);
  }

  function sortIndicator(field: string): string {
    const [currentField, currentDirection] = sort.split(",");
    if (currentField !== field) return "";
    return currentDirection === "asc" ? " ▲" : " ▼";
  }

  async function handleExport() {
    try {
      const blob = await exportCsv({
        status: status || undefined,
        customerId: customerId || undefined,
        slaBreached: slaBreached ? slaBreached === "true" : undefined,
        stuck: stuck ? stuck === "true" : undefined,
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "work-queue.csv";
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      notifications.show({ color: "red", title: "Export failed", message: "Please try again." });
    }
  }

  return (
    <Stack gap="lg">
      <PageHeading>Work Queue</PageHeading>

      <Card withBorder padding="md">
        <Group align="flex-end" gap="sm" wrap="wrap">
          <Select
            label="Status"
            placeholder="Any"
            clearable
            data={ORDER_STATUSES}
            value={status || null}
            onChange={(value) => updateParam("status", value ?? "")}
          />
          <TextInput
            label="Customer ID"
            placeholder="UUID"
            value={customerId}
            onChange={(event) => updateParam("customerId", event.currentTarget.value)}
          />
          <Select
            label="SLA breached"
            placeholder="Any"
            clearable
            data={[
              { value: "true", label: "Breached" },
              { value: "false", label: "Not breached" },
            ]}
            value={slaBreached || null}
            onChange={(value) => updateParam("slaBreached", value ?? "")}
          />
          <Select
            label="Stuck"
            placeholder="Any"
            clearable
            data={[
              { value: "true", label: "Stuck" },
              { value: "false", label: "Not stuck" },
            ]}
            value={stuck || null}
            onChange={(value) => updateParam("stuck", value ?? "")}
          />
          <Button variant="light" leftSection={<IconDownload size={16} />} onClick={handleExport}>
            Export CSV
          </Button>
        </Group>
      </Card>

      {workQueue.isLoading && <LoadingState label="Loading work queue" />}
      {workQueue.isError && <ErrorState error={workQueue.error} />}
      {workQueue.data && workQueue.data.content.length === 0 && (
        <EmptyState message="No orders match these filters." />
      )}
      {workQueue.data && workQueue.data.content.length > 0 && (
        <Card withBorder padding="md">
          <Table stickyHeader>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>
                  <button
                    type="button"
                    onClick={() => toggleSort("createdAt")}
                    style={{ all: "unset", cursor: "pointer" }}
                  >
                    Created{sortIndicator("createdAt")}
                  </button>
                </Table.Th>
                <Table.Th>Order</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Total</Table.Th>
                <Table.Th>Age in stage</Table.Th>
                <Table.Th>Incidents</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {workQueue.data.content.map((item) => (
                <Table.Tr key={item.orderId}>
                  <Table.Td>{formatDateTime(item.createdAt)}</Table.Td>
                  <Table.Td>
                    <Text component={Link} to={`/orders/${item.orderId}`} size="sm" c="indigo.9">
                      {item.orderId.slice(0, 8)}…
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <StatusBadge status={item.status} />
                  </Table.Td>
                  <Table.Td>{formatMoney(item.totalAmount)}</Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      <Badge variant="outline" color="gray">
                        {ageSince(item.currentStageEnteredAt)}
                      </Badge>
                      {filters.slaBreached === true && (
                        <Badge variant="light" color="red">
                          SLA breached
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>{item.openIncidentCount}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>

          <Group justify="center" mt="md">
            <Pagination
              total={workQueue.data.page.totalPages}
              value={page + 1}
              onChange={(nextPage) => setPage(nextPage - 1)}
              getControlProps={(control) => ({
                "aria-label": control === "previous" ? "Previous page" : "Next page",
              })}
            />
          </Group>
        </Card>
      )}
    </Stack>
  );
}
