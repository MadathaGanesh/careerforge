import { defineConfig, devices } from '@playwright/test';

const browser = process.env.CF_BROWSER_EXECUTABLE;
const matrix = process.env.CF_BROWSER_MATRIX === 'true';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: process.env.CF_WEB_ORIGIN || 'http://localhost:5173',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], launchOptions: browser ? { executablePath: browser } : undefined } },
    ...(matrix ? [
      { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
      { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    ] : []),
  ],
});
