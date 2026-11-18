import { defineConfig, devices } from '@playwright/test';
import { frontDoorOrigin, idpOrigin } from './specs/support/test';

const reportDir = process.env.E2E_REPORT_DIR ?? './reports';
const upstream = process.env.E2E_UPSTREAM;
const idpUpstream = process.env.E2E_IDP_UPSTREAM;
if (!upstream || !idpUpstream) {
  throw new Error(
    'E2E_UPSTREAM and E2E_IDP_UPSTREAM (host:port of the front door and of the identity provider) must be set',
  );
}

const hostMappings = [
  `MAP ${new URL(frontDoorOrigin).host} ${upstream}`,
  `MAP ${new URL(idpOrigin).host} ${idpUpstream}`,
];

export default defineConfig({
  testDir: './specs',
  forbidOnly: true,
  retries: 0,
  outputDir: `${reportDir}/artifacts`,
  reporter: [['list'], ['html', { outputFolder: `${reportDir}/html`, open: 'never' }]],
  use: {
    baseURL: frontDoorOrigin,
    launchOptions: {
      args: [`--host-resolver-rules=${hostMappings.join(', ')}`],
    },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
