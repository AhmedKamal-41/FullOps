import { Button, Group, Modal, Stack, Text, TextInput } from "@mantine/core";
import { useState } from "react";
import type { FulfillmentResponse } from "../api/types";

const NEXT_STATUS: Record<string, "PICKING" | "PACKED" | "DISPATCHED" | "DELIVERED" | null> = {
  ASSIGNED: "PICKING",
  PICKING: "PACKED",
  PACKED: "DISPATCHED",
  DISPATCHED: "DELIVERED",
  DELIVERED: null,
  CANCELLED: null,
};

export function nextFulfillmentStatus(status: string) {
  return NEXT_STATUS[status] ?? null;
}

export function AdvanceFulfillmentModal({
  opened,
  onClose,
  fulfillment,
  isSubmitting,
  onConfirm,
}: {
  opened: boolean;
  onClose: () => void;
  fulfillment: FulfillmentResponse;
  isSubmitting: boolean;
  onConfirm: (fields: { trackingReference?: string; deliveredAt?: string }) => void;
}) {
  const [trackingReference, setTrackingReference] = useState("");
  const [deliveredAt, setDeliveredAt] = useState("");

  const nextStatus = nextFulfillmentStatus(fulfillment.status);
  if (!nextStatus) {
    return null;
  }

  const needsTracking = nextStatus === "DISPATCHED";
  const needsDeliveredAt = nextStatus === "DELIVERED";
  const canConfirm =
    (!needsTracking || trackingReference.trim().length > 0) &&
    (!needsDeliveredAt || deliveredAt.trim().length > 0);

  function handleClose() {
    setTrackingReference("");
    setDeliveredAt("");
    onClose();
  }

  return (
    <Modal opened={opened} onClose={handleClose} title={`Advance to ${nextStatus}`}>
      <Stack gap="sm">
        <Text size="sm">
          Move fulfillment {fulfillment.fulfillmentId.slice(0, 8)}… from {fulfillment.status} to{" "}
          {nextStatus}.
        </Text>
        {needsTracking && (
          <TextInput
            label="Tracking reference"
            required
            value={trackingReference}
            onChange={(event) => setTrackingReference(event.currentTarget.value)}
          />
        )}
        {needsDeliveredAt && (
          <TextInput
            type="datetime-local"
            label="Delivered at"
            required
            value={deliveredAt}
            onChange={(event) => setDeliveredAt(event.currentTarget.value)}
          />
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            disabled={!canConfirm}
            loading={isSubmitting}
            onClick={() =>
              onConfirm({
                trackingReference: needsTracking ? trackingReference.trim() : undefined,
                deliveredAt: needsDeliveredAt ? new Date(deliveredAt).toISOString() : undefined,
              })
            }
          >
            Confirm
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
