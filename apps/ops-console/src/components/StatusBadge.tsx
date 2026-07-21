import { Badge } from "@mantine/core";
import { formatStatusLabel } from "../format";

const STATUS_COLORS: Record<string, string> = {
  PENDING: "gray",
  INVENTORY_RESERVED: "blue",
  PAYMENT_AUTHORIZED: "blue",
  FULFILLMENT_ASSIGNED: "blue",
  PICKING: "indigo",
  PACKED: "indigo",
  ASSIGNED: "indigo",
  DISPATCHED: "grape",
  DELIVERED: "green",
  CANCELLATION_PENDING: "orange",
  CANCELLED: "red",
  REQUIRES_REVIEW: "red",
  OPEN: "red",
  ACKNOWLEDGED: "orange",
  RESOLVED: "green",
  PENDING_REVIEW: "orange",
  REPLAYED: "green",
};

// Color is always paired with the status word itself, never used alone to convey
// meaning (WCAG 1.4.1) — the badge text is the real signal, color just reinforces it.
//
// variant="light" (colored text on a tinted background) instead of the default filled
// (white text on a solid color): filled white-on-color badges measured as low as 2.36:1
// against WCAG AA's required 4.5:1 for several hues even at Mantine's darkest shade —
// caught by the E2E axe-core check in e2e/overview.spec.ts against real rendered data.
// The light variant's colored-text-on-tint pattern passes reliably across every hue used
// here without per-color shade tuning.
export function StatusBadge({ status }: { status: string }) {
  return (
    <Badge variant="light" color={STATUS_COLORS[status] ?? "gray"}>
      {formatStatusLabel(status)}
    </Badge>
  );
}
