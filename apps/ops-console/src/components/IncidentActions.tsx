import { Group, Button } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { ConfirmModal } from "./ConfirmModal";
import { useAcknowledgeIncident, useAssignIncident, useResolveIncident } from "../api/hooks";
import type { IncidentResponse } from "../api/types";

export function IncidentActions({ incident }: { incident: IncidentResponse }) {
  const [ackOpened, ackHandlers] = useDisclosure(false);
  const [assignOpened, assignHandlers] = useDisclosure(false);
  const [resolveOpened, resolveHandlers] = useDisclosure(false);

  const acknowledge = useAcknowledgeIncident(incident.incidentId);
  const assign = useAssignIncident(incident.incidentId);
  const resolve = useResolveIncident(incident.incidentId);

  function onError(action: string) {
    notifications.show({ color: "red", title: `${action} failed`, message: "Please try again." });
  }

  return (
    <Group gap="xs">
      {incident.status === "OPEN" && (
        <>
          <Button
            size="xs"
            variant="light"
            onClick={ackHandlers.open}
            loading={acknowledge.isPending}
          >
            Acknowledge
          </Button>
          <ConfirmModal
            opened={ackOpened}
            onClose={ackHandlers.close}
            title="Acknowledge incident"
            description="Mark this incident as acknowledged. It stays open until resolved."
            confirmLabel="Acknowledge"
            confirmColor="orange"
            isSubmitting={acknowledge.isPending}
            onConfirm={() =>
              acknowledge.mutate(undefined, {
                onSuccess: ackHandlers.close,
                onError: () => onError("Acknowledge"),
              })
            }
          />
        </>
      )}

      {incident.status !== "RESOLVED" && (
        <>
          <Button
            size="xs"
            variant="light"
            onClick={assignHandlers.open}
            loading={assign.isPending}
          >
            Assign
          </Button>
          <ConfirmModal
            opened={assignOpened}
            onClose={assignHandlers.close}
            title="Assign incident"
            description="Enter the operator this incident should be assigned to."
            confirmLabel="Assign"
            confirmColor="indigo"
            requireReason
            reasonLabel="Assignee"
            isSubmitting={assign.isPending}
            onConfirm={(assignee) =>
              assign.mutate(assignee, {
                onSuccess: assignHandlers.close,
                onError: () => onError("Assign"),
              })
            }
          />
        </>
      )}

      {incident.status !== "RESOLVED" && (
        <>
          <Button
            size="xs"
            variant="light"
            color="green"
            onClick={resolveHandlers.open}
            loading={resolve.isPending}
          >
            Resolve
          </Button>
          <ConfirmModal
            opened={resolveOpened}
            onClose={resolveHandlers.close}
            title="Resolve incident"
            description="Mark this incident as resolved. An optional note is included in its history."
            confirmLabel="Resolve"
            confirmColor="green"
            showReason
            reasonLabel="Resolution note (optional)"
            isSubmitting={resolve.isPending}
            onConfirm={(note) =>
              resolve.mutate(note || undefined, {
                onSuccess: resolveHandlers.close,
                onError: () => onError("Resolve"),
              })
            }
          />
        </>
      )}
    </Group>
  );
}
