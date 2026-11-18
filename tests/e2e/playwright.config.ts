import { defineConfig, devices } from '@playwright/test';
import { frontDoorOrigin } from './specs/support/test';

const reportDir = process.env.E2E_REPORT_DIR ?? './reports';
const upstream = process.env.E2E_UPSTREAM;
if (!upstream) {
  throw new Error('E2E_UPSTREAM (host:port of the front door) must be set');
}

export default defineConfig({
  testDir: './specs',
  forbidOnly: true,
  retries: 0,
  outputDir: `${reportDir}/artifacts`,
  reporter: [['list'], ['html', { outputFolder: `${reportDir}/html`, open: 'never' }]],
  use: {
    baseURL: frontDoorOrigin,
    launchOptions: {
      args: [`--host-resolver-rules=MAP ${new URL(frontDoorOrigin).host} ${upstream}`],
    },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
