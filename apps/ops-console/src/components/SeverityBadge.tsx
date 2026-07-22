import { Badge } from "@mantine/core";
import type { IncidentKind } from "../api/types";

// Presentational only — the backend has no "severity" concept for IncidentKind. This is
// a client-side triage aid, not a new backend concept.
const SEVERITY: Record<IncidentKind, { label: string; color: string }> = {
  COMPENSATION_EXHAUSTED: { label: "High", color: "red" },
  CANCELLATION_AFTER_DISPATCH: { label: "Medium", color: "orange" },
  CANCELLATION_STUCK: { label: "Low", color: "yellow" },
};

// variant="light" — see StatusBadge.tsx for why (filled white-on-color badges failed
// WCAG AA contrast even at the darkest available shade for several hues).
export function SeverityBadge({ kind }: { kind: IncidentKind }) {
  const severity = SEVERITY[kind];
  return (
    <Badge variant="light" color={severity.color}>
      {severity.label} severity
    </Badge>
  );
}
