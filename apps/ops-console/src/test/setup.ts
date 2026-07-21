import "@testing-library/jest-dom/vitest";
import { toHaveNoViolations } from "jest-axe";
import { afterAll, afterEach, beforeAll, expect, vi } from "vitest";
import { server } from "./server";

expect.extend(toHaveNoViolations);

// jsdom has no matchMedia implementation; Mantine's color-scheme detection needs one.
window.matchMedia ??= vi.fn().mockImplementation((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: vi.fn(),
  removeListener: vi.fn(),
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
  dispatchEvent: vi.fn(),
}));

// jsdom has no ResizeObserver either; Mantine's ScrollArea/Table/Textarea autosize all use it.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver ??= ResizeObserverStub;

// jsdom has no Font Loading API; Mantine's Textarea autosize listens for it.
// @ts-expect-error -- test-only stub, not a spec-complete FontFaceSet
document.fonts ??= { addEventListener: vi.fn(), removeEventListener: vi.fn() };

// jsdom has no scrollIntoView; Mantine's Combobox calls it whenever an option becomes
// selected, and the missing method throws, silently aborting the rest of that render.
Element.prototype.scrollIntoView = vi.fn();

// jsdom always reports zero-size layout rects, which leaves Floating-UI-positioned
// elements (Mantine's Select/Combobox dropdowns) stuck at their initial `display: none`
// — Floating UI never gets a real measurement to position against, so it never flips it
// visible. A fixed non-zero rect is enough for positioning to settle in tests.
Element.prototype.getBoundingClientRect = () => ({
  width: 120,
  height: 32,
  top: 0,
  left: 0,
  bottom: 32,
  right: 120,
  x: 0,
  y: 0,
  toJSON() {},
});

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
