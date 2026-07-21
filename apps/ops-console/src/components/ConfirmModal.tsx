import { Button, Group, Modal, Stack, Text, Textarea } from "@mantine/core";
import { useState } from "react";

export interface ConfirmModalProps {
  opened: boolean;
  onClose: () => void;
  title: string;
  description: string;
  confirmLabel: string;
  confirmColor?: string;
  requireReason?: boolean;
  showReason?: boolean;
  reasonLabel?: string;
  isSubmitting?: boolean;
  onConfirm: (reason: string) => void;
}

export function ConfirmModal({
  opened,
  onClose,
  title,
  description,
  confirmLabel,
  confirmColor = "red",
  requireReason = false,
  showReason = requireReason,
  reasonLabel = "Reason",
  isSubmitting = false,
  onConfirm,
}: ConfirmModalProps) {
  const [reason, setReason] = useState("");
  const canConfirm = !requireReason || reason.trim().length > 0;

  function handleClose() {
    setReason("");
    onClose();
  }

  return (
    <Modal opened={opened} onClose={handleClose} title={title}>
      <Stack gap="sm">
        <Text size="sm">{description}</Text>
        {showReason && (
          <Textarea
            label={reasonLabel}
            required={requireReason}
            value={reason}
            onChange={(event) => setReason(event.currentTarget.value)}
            autosize
            minRows={2}
          />
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            color={confirmColor}
            disabled={!canConfirm}
            loading={isSubmitting}
            onClick={() => onConfirm(reason.trim())}
          >
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
