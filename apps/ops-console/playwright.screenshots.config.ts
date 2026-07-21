import { defineConfig, devices } from "@playwright/test";

// A separate, deliberately narrow config: only e2e-screenshots/, run via `npm run
// screenshots`, never part of the normal `npm run e2e` suite.
export default defineConfig({
  testDir: "./e2e-screenshots",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:5173",
    viewport: { width: 1280, height: 800 },
    launchOptions: { args: ["--disable-dev-shm-usage"] },
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
