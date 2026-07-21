import { Badge, Card, Group, Stack, Table, Text, Title } from "@mantine/core";
import { BarChart } from "@mantine/charts";
import { PageHeading } from "../components/PageHeading";
import { DateRangeControl, useDateRangeState } from "../components/DateRangeControl";
import { ChartTableToggle } from "../components/ChartTableToggle";
import { EmptyState, ErrorState, LoadingState } from "../components/QueryState";
import { useLowStock, useOverviewKpis } from "../api/hooks";
import { formatDateTime, formatPercent } from "../format";

export function InventoryRisk() {
  const lowStock = useLowStock();
  const [range, setRange] = useDateRangeState();
  const overview = useOverviewKpis(range);

  return (
    <Stack gap="lg">
      <PageHeading>Inventory Risk</PageHeading>

      <Card withBorder padding="md">
        <Title order={2} size="h4" mb="sm">
          Low-stock SKUs
        </Title>
        {lowStock.isLoading && <LoadingState label="Loading low-stock signals" />}
        {lowStock.isError && <ErrorState error={lowStock.error} />}
        {lowStock.data && lowStock.data.length === 0 && (
          <EmptyState message="No SKUs below their reorder threshold." />
        )}
        {lowStock.data && lowStock.data.length > 0 && (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>SKU</Table.Th>
                <Table.Th>Available</Table.Th>
                <Table.Th>Threshold</Table.Th>
                <Table.Th>Signal</Table.Th>
                <Table.Th>Detected</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {lowStock.data.map((signal) => (
                <Table.Tr key={signal.sku}>
                  <Table.Td>{signal.sku}</Table.Td>
                  <Table.Td>{signal.availableQuantity}</Table.Td>
                  <Table.Td>{signal.threshold}</Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      <Badge
                        variant="light"
                        color={signal.availableQuantity === 0 ? "red" : "orange"}
                      >
                        {signal.availableQuantity === 0 ? "Out of stock" : "Below threshold"}
                      </Badge>
                    </Group>
                  </Table.Td>
                  <Table.Td>{formatDateTime(signal.occurredAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>

      <Card withBorder padding="md">
        <Group justify="space-between" align="flex-end" mb="sm">
          <Title order={2} size="h4">
            Rejection trend
          </Title>
          <DateRangeControl value={range} onChange={setRange} />
        </Group>
        {overview.isLoading && <LoadingState label="Loading rejection trend" />}
        {overview.isError && <ErrorState error={overview.error} />}
        {overview.data && (
          <Stack gap="sm">
            <Text size="sm" c="dimmed">
              Inventory rejection rate: {formatPercent(overview.data.inventoryRejectionRate)} (
              {overview.data.inventoryRejections} of {overview.data.ordersReceived} orders)
            </Text>
            {overview.data.inventoryRejectionReasons.length === 0 ? (
              <EmptyState message="No inventory rejections in this range." />
            ) : (
              <ChartTableToggle
                chart={
                  <BarChart
                    h={220}
                    data={overview.data.inventoryRejectionReasons.map((r) => ({
                      reason: r.reasonCode,
                      orders: r.orderCount,
                    }))}
                    dataKey="reason"
                    series={[{ name: "orders", color: "orange.6" }]}
                  />
                }
                table={
                  <Table mt="sm">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Reason</Table.Th>
                        <Table.Th>Orders</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {overview.data.inventoryRejectionReasons.map((r) => (
                        <Table.Tr key={r.reasonCode}>
                          <Table.Td>{r.reasonCode}</Table.Td>
                          <Table.Td>{r.orderCount}</Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                }
              />
            )}
          </Stack>
        )}
      </Card>
    </Stack>
  );
}
