import { Alert, Center, Loader, Text } from "@mantine/core";
import { IconAlertCircle } from "@tabler/icons-react";
import { ApiError } from "../api/errors";

// Every data-fetching route renders one of these three states honestly instead of
// silently showing stale or blank content — see the phase brief's "truthful
// empty/loading/error states" requirement.
export function LoadingState({ label }: { label: string }) {
  return (
    <Center py="xl">
      <Loader aria-label={label} />
    </Center>
  );
}

export function ErrorState({ error }: { error: unknown }) {
  const message =
    error instanceof ApiError
      ? (error.problem?.detail ?? error.message)
      : error instanceof Error
        ? error.message
        : "An unexpected error occurred.";

  return (
    <Alert color="red" icon={<IconAlertCircle size={18} />} title="Couldn't load this data">
      <Text size="sm">{message}</Text>
    </Alert>
  );
}

export function EmptyState({ message }: { message: string }) {
  return (
    <Text c="dimmed" py="xl" ta="center">
      {message}
    </Text>
  );
}
