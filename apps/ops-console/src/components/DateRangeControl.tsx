import { Group, TextInput } from "@mantine/core";
import { useState } from "react";

function isoDateDaysAgo(days: number): string {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}

export interface DateRangeState {
  fromDate: string;
  toDate: string;
  from: string;
  to: string;
  [key: string]: string;
}

export function useDateRangeState(): [DateRangeState, (fromDate: string, toDate: string) => void] {
  const [fromDate, setFromDate] = useState(() => isoDateDaysAgo(7));
  const [toDate, setToDate] = useState(() => isoDateDaysAgo(0));

  const state: DateRangeState = {
    fromDate,
    toDate,
    from: `${fromDate}T00:00:00Z`,
    to: `${toDate}T23:59:59Z`,
  };

  const setRange = (nextFrom: string, nextTo: string) => {
    setFromDate(nextFrom);
    setToDate(nextTo);
  };

  return [state, setRange];
}

export function DateRangeControl({
  value,
  onChange,
}: {
  value: DateRangeState;
  onChange: (fromDate: string, toDate: string) => void;
}) {
  return (
    <Group align="flex-end" gap="sm">
      <TextInput
        type="date"
        label="From"
        value={value.fromDate}
        max={value.toDate}
        onChange={(event) => onChange(event.currentTarget.value, value.toDate)}
      />
      <TextInput
        type="date"
        label="To"
        value={value.toDate}
        min={value.fromDate}
        onChange={(event) => onChange(value.fromDate, event.currentTarget.value)}
      />
    </Group>
  );
}
