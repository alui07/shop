import { test as base, type APIRequest, type APIRequestContext } from '@playwright/test';

export { expect } from '@playwright/test';

export const frontDoorOrigin = 'http://localhost:8088';
export const idpOrigin = 'http://localhost:8089';
export const issuer = `${idpOrigin}/realms/shop`;
const upstream = process.env.E2E_UPSTREAM ?? '';
const idpUpstream = process.env.E2E_IDP_UPSTREAM ?? '';

// The browser reaches the front door as http://localhost:8088 and the identity provider as
// http://localhost:8089 through Chromium host mappings. The Node-side request contexts do not use
// Chromium's resolver, so they connect to the upstream directly and send the same Host header.
function upstreamContext(
  factory: APIRequest,
  upstreamHostPort: string,
  origin: string,
): Promise<APIRequestContext> {
  return factory.newContext({
    baseURL: `http://${upstreamHostPort}`,
    extraHTTPHeaders: { Host: new URL(origin).host },
  });
}

export const test = base.extend<{ idpRequest: APIRequestContext }>({
  request: async ({ playwright }, use) => {
    const context = await upstreamContext(playwright.request, upstream, frontDoorOrigin);
    await use(context);
    await context.dispose();
  },
  idpRequest: async ({ playwright }, use) => {
    const context = await upstreamContext(playwright.request, idpUpstream, idpOrigin);
    await use(context);
    await context.dispose();
  },
});
