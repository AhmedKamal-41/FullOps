import { Title } from "@mantine/core";
import { useEffect, useRef } from "react";

// Every route renders exactly one of these as its <h1>. Moving focus here on mount is
// what makes route changes announced to screen-reader / keyboard users — without it,
// focus silently stays wherever the previous page left it.
export function PageHeading({ children }: { children: string }) {
  const ref = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    ref.current?.focus();
  }, []);

  return (
    <Title order={1} mb="md" ref={ref} tabIndex={-1} style={{ outline: "none" }}>
      {children}
    </Title>
  );
}
