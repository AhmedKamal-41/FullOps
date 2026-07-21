import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  // The default 5s assertion timeout is tuned for a machine that isn't also running
  // four local JVMs, Kafka, and Keycloak at once — under that load a real render can
  // legitimately take longer than 5s without anything being wrong. 15s is still a
  // real bound, not a mask for a genuine hang.
  expect: { timeout: 15_000 },
  use: {
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    // This sandbox is memory-constrained (four local JVMs, Kafka, Keycloak, Postgres
    // all running at once) with a far smaller /dev/shm than Chromium expects, so the
    // renderer occasionally crashes under load — not an app bug. These flags make
    // Chromium spill shared memory to /tmp and skip sandboxing/GPU overhead it
    // doesn't need for headless testing; `retries: 1` absorbs anything that still
    // gets through.
    launchOptions: {
      args: ["--disable-dev-shm-usage", "--disable-gpu", "--no-sandbox"],
    },
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
