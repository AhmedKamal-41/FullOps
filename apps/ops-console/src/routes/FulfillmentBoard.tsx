import { Card, Grid, Group, Stack, Text, Title } from "@mantine/core";
import { Link } from "react-router-dom";
import { PageHeading } from "../components/PageHeading";
import { FulfillmentActions } from "../components/FulfillmentActions";
import { EmptyState, ErrorState, LoadingState } from "../components/QueryState";
import { useFulfillments } from "../api/fulfillmentHooks";
import { formatDateTime } from "../format";
import type { FulfillmentStatus } from "../api/types";

const STAGES: FulfillmentStatus[] = ["ASSIGNED", "PICKING", "PACKED", "DISPATCHED"];

function isBreached(slaDueAt: string): boolean {
  return new Date(slaDueAt).getTime() < Date.now();
}

function StageColumn({ status }: { status: FulfillmentStatus }) {
  const fulfillments = useFulfillments(status);

  return (
    <Card withBorder padding="md" h="100%">
      <Title order={2} size="h4" mb="sm">
        {status}
      </Title>
      {fulfillments.isLoading && <LoadingState label={`Loading ${status} fulfillments`} />}
      {fulfillments.isError && <ErrorState error={fulfillments.error} />}
      {fulfillments.data && fulfillments.data.content.length === 0 && (
        <EmptyState message="Nothing in this stage." />
      )}
      {fulfillments.data && fulfillments.data.content.length > 0 && (
        <Stack gap="sm">
          {fulfillments.data.content.map((fulfillment) => (
            <Card key={fulfillment.fulfillmentId} withBorder padding="sm">
              <Group justify="space-between" align="flex-start" mb={4}>
                <Text
                  component={Link}
                  to={`/orders/${fulfillment.orderId}`}
                  size="sm"
                  fw={500}
                  c="indigo.9"
                >
                  {fulfillment.orderId.slice(0, 8)}…
                </Text>
                {isBreached(fulfillment.slaDueAt) && (
                  <Text size="xs" c="red.9" fw={600}>
                    SLA due {formatDateTime(fulfillment.slaDueAt)}
                  </Text>
                )}
              </Group>
              <Text size="xs" c="dimmed" mb="xs">
                {fulfillment.assigneeId ? `Assigned to ${fulfillment.assigneeId}` : "Unclaimed"}
              </Text>
              <FulfillmentActions fulfillment={fulfillment} />
            </Card>
          ))}
        </Stack>
      )}
    </Card>
  );
}

export function FulfillmentBoard() {
  return (
    <Stack gap="lg">
      <PageHeading>Fulfillment Board</PageHeading>
      <Grid>
        {STAGES.map((stage) => (
          <Grid.Col key={stage} span={{ base: 12, sm: 6, lg: 3 }}>
            <StageColumn status={stage} />
          </Grid.Col>
        ))}
      </Grid>
    </Stack>
  );
}
