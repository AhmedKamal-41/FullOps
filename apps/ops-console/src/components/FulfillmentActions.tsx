import { Button, Group } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { ConfirmModal } from "./ConfirmModal";
import { AdvanceFulfillmentModal, nextFulfillmentStatus } from "./AdvanceFulfillmentModal";
import {
  useAdvanceFulfillment,
  useCancelFulfillment,
  useClaimFulfillment,
} from "../api/fulfillmentHooks";
import type { FulfillmentResponse } from "../api/types";

export function FulfillmentActions({ fulfillment }: { fulfillment: FulfillmentResponse }) {
  const [advanceOpened, advanceHandlers] = useDisclosure(false);
  const [cancelOpened, cancelHandlers] = useDisclosure(false);

  const claim = useClaimFulfillment(fulfillment.fulfillmentId);
  const advance = useAdvanceFulfillment(fulfillment.fulfillmentId);
  const cancelFulfillment = useCancelFulfillment(fulfillment.fulfillmentId);

  const isTerminal = fulfillment.status === "DELIVERED" || fulfillment.status === "CANCELLED";
  const nextStatus = nextFulfillmentStatus(fulfillment.status);

  function onError(action: string) {
    notifications.show({ color: "red", title: `${action} failed`, message: "Please try again." });
  }

  if (isTerminal) {
    return null;
  }

  return (
    <Group gap="xs">
      {!fulfillment.assigneeId && (
        <Button
          size="xs"
          variant="light"
          loading={claim.isPending}
          onClick={() => claim.mutate(fulfillment.version, { onError: () => onError("Claim") })}
        >
          Claim
        </Button>
      )}

      {nextStatus && (
        <>
          <Button size="xs" variant="light" color="indigo" onClick={advanceHandlers.open}>
            Advance
          </Button>
          <AdvanceFulfillmentModal
            opened={advanceOpened}
            onClose={advanceHandlers.close}
            fulfillment={fulfillment}
            isSubmitting={advance.isPending}
            onConfirm={(fields) =>
              advance.mutate(
                {
                  version: fulfillment.version,
                  request: { newStatus: nextStatus, ...fields },
                },
                { onSuccess: advanceHandlers.close, onError: () => onError("Advance") },
              )
            }
          />
        </>
      )}

      <Button size="xs" variant="light" color="red" onClick={cancelHandlers.open}>
        Cancel
      </Button>
      <ConfirmModal
        opened={cancelOpened}
        onClose={cancelHandlers.close}
        title="Cancel fulfillment"
        description="This cancels the fulfillment. A reason is required for the audit trail."
        confirmLabel="Cancel fulfillment"
        requireReason
        reasonLabel="Reason"
        isSubmitting={cancelFulfillment.isPending}
        onConfirm={(reasonDetail) =>
          cancelFulfillment.mutate(
            { version: fulfillment.version, reasonDetail },
            { onSuccess: cancelHandlers.close, onError: () => onError("Cancel") },
          )
        }
      />
    </Group>
  );
}
