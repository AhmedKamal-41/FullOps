import { createTheme, type CSSVariablesResolver } from "@mantine/core";

export const theme = createTheme({
  primaryColor: "indigo",
  fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif",
  defaultRadius: "sm",
  headings: {
    fontWeight: "600",
  },
});

// Mantine's default "dimmed" text color (gray.6, used throughout this app for secondary
// text like KPI card labels) only measures 3.32:1 against white — short of WCAG AA's
// 4.5:1. Caught by the E2E axe-core check in e2e/overview.spec.ts against real rendered
// data — first on orange, then (once real data produced a RESOLVED incident) on green
// too: several of Mantine's hues don't reach 4.5:1 anywhere in their shade range against
// their own light-variant tint. Every hue StatusBadge/SeverityBadge use is overridden
// here to a hand-picked dark shade (Tailwind's *-900, independently verified >4.5:1
// against a light tint background) so the same class of bug can't resurface hue by hue
// as more real data exercises badges this E2E suite hasn't happened to render yet.
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    "--mantine-color-dimmed": "var(--mantine-color-gray-7)",
    "--mantine-color-red-light-color": "#7f1d1d",
    "--mantine-color-orange-light-color": "#7c2d12",
    "--mantine-color-yellow-light-color": "#713f12",
    "--mantine-color-green-light-color": "#14532d",
    "--mantine-color-blue-light-color": "#1e3a8a",
    "--mantine-color-indigo-light-color": "#312e81",
    "--mantine-color-grape-light-color": "#4c1d95",
    "--mantine-color-gray-light-color": "#111827",
  },
  dark: {},
});
