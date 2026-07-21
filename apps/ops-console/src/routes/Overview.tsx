import { Card, Grid, Group, Stack, Table, Text, Title } from "@mantine/core";
import { LineChart, BarChart } from "@mantine/charts";
import { Link } from "react-router-dom";
import { PageHeading } from "../components/PageHeading";
import { DateRangeControl, useDateRangeState } from "../components/DateRangeControl";
import { KpiCard } from "../components/KpiCard";
import { ChartTableToggle } from "../components/ChartTableToggle";
import { StatusBadge } from "../components/StatusBadge";
import { LoadingState, ErrorState, EmptyState } from "../components/QueryState";
import { useBacklog, useIncidents, useOverviewKpis, useTimeseries } from "../api/hooks";
import { formatDateTime, formatPercent } from "../format";

export function Overview() {
  const [range, setRange] = useDateRangeState();
  const overview = useOverviewKpis(range);
  const timeseries = useTimeseries(range);
  const backlog = useBacklog();
  const recentIncidents = useIncidents({ sort: "createdAt,desc", size: 5 });

  return (
    <Stack gap="lg">
      <PageHeading>Overview</PageHeading>

      <DateRangeControl value={range} onChange={setRange} />

      {overview.isLoading && <LoadingState label="Loading KPIs" />}
      {overview.isError && <ErrorState error={overview.error} />}
      {overview.data && (
        <Grid>
          <Grid.Col span={{ base: 6, sm: 3 }}>
            <KpiCard label="Orders received" value={overview.data.ordersReceived.toString()} />
          </Grid.Col>
          <Grid.Col span={{ base: 6, sm: 3 }}>
            <KpiCard label="Orders completed" value={overview.data.ordersCompleted.toString()} />
          </Grid.Col>
          <Grid.Col span={{ base: 6, sm: 3 }}>
            <KpiCard
              label="Inventory rejection rate"
              value={formatPercent(overview.data.inventoryRejectionRate)}
              hint={`${overview.data.inventoryRejections} orders`}
            />
          </Grid.Col>
          <Grid.Col span={{ base: 6, sm: 3 }}>
            <KpiCard
              label="Payment decline rate"
              value={formatPercent(overview.data.paymentDeclineRate)}
              hint={`${overview.data.paymentDeclines} orders`}
            />
          </Grid.Col>
          <Grid.Col span={{ base: 6, sm: 3 }}>
            <KpiCard
              label="Recovery success rate"
              value={formatPercent(overview.data.recoverySuccessRate)}
            />
          </Grid.Col>
          <Grid.Col span={{ base: 6, sm: 3 }}>
            <KpiCard
              label="Manual-touch rate"
              value={formatPercent(overview.data.manualTouchRate)}
            />
          </Grid.Col>
          <Grid.Col span={{ base: 6, sm: 3 }}>
            <KpiCard label="Dead-letter backlog" value={overview.data.dltBacklogCount.toString()} />
          </Grid.Col>
          <Grid.Col span={{ base: 6, sm: 3 }}>
            <KpiCard label="Outbox backlog" value={overview.data.outboxBacklogCount.toString()} />
          </Grid.Col>
        </Grid>
      )}

      <Card withBorder padding="md">
        <Title order={2} size="h4" mb="sm">
          Order throughput
        </Title>
        {timeseries.isLoading && <LoadingState label="Loading throughput chart" />}
        {timeseries.isError && <ErrorState error={timeseries.error} />}
        {timeseries.data && (
          <ChartTableToggle
            chart={
              <LineChart
                h={280}
                data={timeseries.data.points.map((point) => ({
                  date: formatDateTime(point.bucketStart).split(",")[0],
                  Received: point.ordersReceived,
                  Completed: point.ordersCompleted,
                  Cancelled: point.ordersCancelled,
                }))}
                dataKey="date"
                series={[
                  { name: "Received", color: "indigo.6" },
                  { name: "Completed", color: "teal.6" },
                  { name: "Cancelled", color: "red.6" },
                ]}
                curveType="linear"
              />
            }
            table={
              <Table mt="sm" data-testid="throughput-table">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Date</Table.Th>
                    <Table.Th>Received</Table.Th>
                    <Table.Th>Completed</Table.Th>
                    <Table.Th>Cancelled</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {timeseries.data.points.map((point) => (
                    <Table.Tr key={point.bucketStart}>
                      <Table.Td>{formatDateTime(point.bucketStart)}</Table.Td>
                      <Table.Td>{point.ordersReceived}</Table.Td>
                      <Table.Td>{point.ordersCompleted}</Table.Td>
                      <Table.Td>{point.ordersCancelled}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            }
          />
        )}
      </Card>

      <Grid>
        <Grid.Col span={{ base: 12, md: 6 }}>
          <Card withBorder padding="md" h="100%">
            <Title order={2} size="h4" mb="sm">
              Backlog by stage / SLA breaches
            </Title>
            {backlog.isLoading && <LoadingState label="Loading backlog" />}
            {backlog.isError && <ErrorState error={backlog.error} />}
            {backlog.data && backlog.data.stages.length === 0 && (
              <EmptyState message="No open orders right now." />
            )}
            {backlog.data && backlog.data.stages.length > 0 && (
              <Table>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Stage</Table.Th>
                    <Table.Th>Open</Table.Th>
                    <Table.Th>SLA breached</Table.Th>
                    <Table.Th>Breach rate</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {backlog.data.stages.map((stage) => (
                    <Table.Tr key={stage.stage}>
                      <Table.Td>
                        <StatusBadge status={stage.stage} />
                      </Table.Td>
                      <Table.Td>{stage.openOrderCount}</Table.Td>
                      <Table.Td>{stage.slaBreachedCount}</Table.Td>
                      <Table.Td>{formatPercent(stage.slaBreachRate)}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Card>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 6 }}>
          <Card withBorder padding="md" h="100%">
            <Title order={2} size="h4" mb="sm">
              Failure reasons
            </Title>
            {overview.data && (
              <ChartTableToggle
                chart={
                  <BarChart
                    h={220}
                    data={[
                      ...overview.data.inventoryRejectionReasons.map((r) => ({
                        reason: r.reasonCode,
                        orders: r.orderCount,
                        type: "Inventory",
                      })),
                      ...overview.data.paymentDeclineReasons.map((r) => ({
                        reason: r.reasonCode,
                        orders: r.orderCount,
                        type: "Payment",
                      })),
                    ]}
                    dataKey="reason"
                    series={[{ name: "orders", color: "red.6" }]}
                  />
                }
                table={
                  <Table mt="sm">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Reason</Table.Th>
                        <Table.Th>Category</Table.Th>
                        <Table.Th>Orders</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {overview.data.inventoryRejectionReasons.map((r) => (
                        <Table.Tr key={`inv-${r.reasonCode}`}>
                          <Table.Td>{r.reasonCode}</Table.Td>
                          <Table.Td>Inventory</Table.Td>
                          <Table.Td>{r.orderCount}</Table.Td>
                        </Table.Tr>
                      ))}
                      {overview.data.paymentDeclineReasons.map((r) => (
                        <Table.Tr key={`pay-${r.reasonCode}`}>
                          <Table.Td>{r.reasonCode}</Table.Td>
                          <Table.Td>Payment</Table.Td>
                          <Table.Td>{r.orderCount}</Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                }
              />
            )}
          </Card>
        </Grid.Col>
      </Grid>

      <Card withBorder padding="md">
        <Title order={2} size="h4" mb="sm">
          Recent incidents
        </Title>
        {recentIncidents.isLoading && <LoadingState label="Loading recent incidents" />}
        {recentIncidents.isError && <ErrorState error={recentIncidents.error} />}
        {recentIncidents.data && recentIncidents.data.content.length === 0 && (
          <EmptyState message="No incidents recorded yet." />
        )}
        {recentIncidents.data && recentIncidents.data.content.length > 0 && (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Order</Table.Th>
                <Table.Th>Kind</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Created</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {recentIncidents.data.content.map((incident) => (
                <Table.Tr key={incident.incidentId}>
                  <Table.Td>
                    <Group gap={4}>
                      <Text
                        component={Link}
                        to={`/orders/${incident.orderId}`}
                        size="sm"
                        c="indigo.9"
                      >
                        {incident.orderId.slice(0, 8)}…
                      </Text>
                    </Group>
                  </Table.Td>
                  <Table.Td>{incident.kind}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={incident.status} />
                  </Table.Td>
                  <Table.Td>{formatDateTime(incident.createdAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>
    </Stack>
  );
}
