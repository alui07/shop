import { test as base } from '@playwright/test';

export { expect } from '@playwright/test';

export const frontDoorOrigin = 'http://localhost:8088';
const frontDoorHost = new URL(frontDoorOrigin).host;
const upstream = process.env.E2E_UPSTREAM ?? '';

// The browser reaches the front door as http://localhost:8088 through a Chromium host mapping.
// The Node-side request context does not use Chromium's resolver, so it connects to the upstream
// directly and sends the same Host header.
export const test = base.extend({
  request: async ({ playwright }, use) => {
    const context = await playwright.request.newContext({
      baseURL: `http://${upstream}`,
      extraHTTPHeaders: { Host: frontDoorHost },
    });
    await use(context);
    await context.dispose();
  },
});
