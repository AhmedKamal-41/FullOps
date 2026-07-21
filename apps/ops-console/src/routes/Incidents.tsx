import { Card, Group, Stack, Table, Tabs, Text } from "@mantine/core";
import { Link, useSearchParams } from "react-router-dom";
import { PageHeading } from "../components/PageHeading";
import { StatusBadge } from "../components/StatusBadge";
import { SeverityBadge } from "../components/SeverityBadge";
import { IncidentActions } from "../components/IncidentActions";
import { DeadLettersPanel } from "../components/DeadLettersPanel";
import { EmptyState, ErrorState, LoadingState } from "../components/QueryState";
import { useIncidents } from "../api/hooks";
import { useIsAdmin } from "../auth/useIsAdmin";
import { formatDateTime } from "../format";
import type { IncidentStatus } from "../api/types";

const TABS: { value: IncidentStatus; label: string }[] = [
  { value: "OPEN", label: "Open" },
  { value: "ACKNOWLEDGED", label: "Assigned" },
  { value: "RESOLVED", label: "Resolved" },
];

export function Incidents() {
  const [searchParams, setSearchParams] = useSearchParams();
  const isAdmin = useIsAdmin();
  const activeTab = (searchParams.get("status") as IncidentStatus) ?? "OPEN";

  const incidents = useIncidents({ status: activeTab, sort: "createdAt,desc", size: 50 });

  function handleTabChange(value: string | null) {
    if (!value) return;
    setSearchParams({ status: value });
  }

  return (
    <Stack gap="lg">
      <PageHeading>Incidents</PageHeading>

      <Tabs value={activeTab} onChange={handleTabChange}>
        <Tabs.List>
          {TABS.map((tab) => (
            <Tabs.Tab key={tab.value} value={tab.value}>
              {tab.label}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>

      <Card withBorder padding="md">
        {incidents.isLoading && <LoadingState label="Loading incidents" />}
        {incidents.isError && <ErrorState error={incidents.error} />}
        {incidents.data && incidents.data.content.length === 0 && (
          <EmptyState message="No incidents in this tab." />
        )}
        {incidents.data && incidents.data.content.length > 0 && (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Order</Table.Th>
                <Table.Th>Kind</Table.Th>
                <Table.Th>Severity</Table.Th>
                <Table.Th>Evidence</Table.Th>
                <Table.Th>Owner</Table.Th>
                <Table.Th>Created</Table.Th>
                <Table.Th>Actions</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {incidents.data.content.map((incident) => (
                <Table.Tr key={incident.incidentId}>
                  <Table.Td>
                    <Text
                      component={Link}
                      to={`/orders/${incident.orderId}`}
                      size="sm"
                      c="indigo.9"
                    >
                      {incident.orderId.slice(0, 8)}…
                    </Text>
                  </Table.Td>
                  <Table.Td>{incident.kind}</Table.Td>
                  <Table.Td>
                    <SeverityBadge kind={incident.kind} />
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" lineClamp={2} maw={280}>
                      {incident.detail}
                    </Text>
                  </Table.Td>
                  <Table.Td>{incident.assignedTo ?? "Unassigned"}</Table.Td>
                  <Table.Td>{formatDateTime(incident.createdAt)}</Table.Td>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <IncidentActions incident={incident} />
                      <StatusBadge status={incident.status} />
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>

      {isAdmin && <DeadLettersPanel />}
    </Stack>
  );
}
