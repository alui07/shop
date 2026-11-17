import { defineConfig, devices } from '@playwright/test';

const reportDir = process.env.E2E_REPORT_DIR ?? './reports';

export default defineConfig({
  testDir: './specs',
  forbidOnly: true,
  retries: 0,
  outputDir: `${reportDir}/artifacts`,
  reporter: [['list'], ['html', { outputFolder: `${reportDir}/html`, open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
