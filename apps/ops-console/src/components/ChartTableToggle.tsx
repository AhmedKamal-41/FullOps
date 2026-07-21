import type { ReactNode } from "react";

// Every chart in this console pairs with this — a text/table equivalent of the same
// data, per the phase brief's accessibility requirement. Charts alone leave
// screen-reader users and anyone who can't parse a visual trend with nothing.
export function ChartTableToggle({ chart, table }: { chart: ReactNode; table: ReactNode }) {
  return (
    <div>
      {chart}
      <details>
        <summary>View as table</summary>
        {table}
      </details>
    </div>
  );
}
