import { Button, Card, Table, Text, Title } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { useState } from "react";
import { ConfirmModal } from "./ConfirmModal";
import { StatusBadge } from "./StatusBadge";
import { EmptyState, ErrorState, LoadingState } from "./QueryState";
import { useDeadLetters, useReplayDeadLetter } from "../api/hooks";
import { formatDateTime } from "../format";

export function DeadLettersPanel() {
  const deadLetters = useDeadLetters();
  const replay = useReplayDeadLetter();
  const [opened, handlers] = useDisclosure(false);
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);

  function openReplayConfirm(eventId: string) {
    setSelectedEventId(eventId);
    handlers.open();
  }

  return (
    <Card withBorder padding="md">
      <Title order={2} size="h4" mb="sm">
        Dead letters (admin)
      </Title>
      {deadLetters.isLoading && <LoadingState label="Loading dead letters" />}
      {deadLetters.isError && <ErrorState error={deadLetters.error} />}
      {deadLetters.data && deadLetters.data.length === 0 && (
        <EmptyState message="No dead-lettered events pending review." />
      )}
      {deadLetters.data && deadLetters.data.length > 0 && (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Event type</Table.Th>
              <Table.Th>Topic</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Created</Table.Th>
              <Table.Th>Action</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {deadLetters.data.map((event) => (
              <Table.Tr key={event.eventId}>
                <Table.Td>{event.eventType}</Table.Td>
                <Table.Td>{event.originalTopic}</Table.Td>
                <Table.Td>
                  <StatusBadge status={event.status} />
                </Table.Td>
                <Table.Td>{formatDateTime(event.createdAt)}</Table.Td>
                <Table.Td>
                  {event.status === "PENDING_REVIEW" && (
                    <Button
                      size="xs"
                      variant="light"
                      onClick={() => openReplayConfirm(event.eventId)}
                    >
                      Replay
                    </Button>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <ConfirmModal
        opened={opened}
        onClose={handlers.close}
        title="Replay event"
        description="This re-delivers the event to its consumer. Only do this once the underlying issue is fixed."
        confirmLabel="Replay"
        confirmColor="indigo"
        isSubmitting={replay.isPending}
        onConfirm={() => {
          if (!selectedEventId) return;
          replay.mutate(selectedEventId, {
            onSuccess: handlers.close,
            onError: () =>
              notifications.show({
                color: "red",
                title: "Replay failed",
                message: "Please try again.",
              }),
          });
        }}
      />
      <Text size="xs" c="dimmed" mt="xs">
        Only PENDING_REVIEW events can be replayed.
      </Text>
    </Card>
  );
}
