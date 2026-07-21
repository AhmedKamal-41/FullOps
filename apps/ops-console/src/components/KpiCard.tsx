import { Card, Text, Title } from "@mantine/core";

export function KpiCard({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card withBorder padding="md">
      <Text size="sm" c="dimmed">
        {label}
      </Text>
      <Title order={2} size="h2">
        {value}
      </Title>
      {hint && (
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      )}
    </Card>
  );
}
