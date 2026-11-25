import { createServer, request as httpRequest } from 'node:http';
import type { AddressInfo } from 'node:net';
import { expect, frontDoorOrigin, issuer, test } from './support/test';

interface RawResponse {
  status: number;
  setCookies: string[];
  contentType: string;
  body: string;
}

// Playwright's request API runs paths through URL parsing, which collapses `/./`; node:http sends the path as written.
function rawPost(
  hostPort: string,
  path: string,
  headers: Record<string, string>,
): Promise<RawResponse> {
  const [hostname, port] = hostPort.split(':');
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest(
      { hostname, port: Number(port), path, method: 'POST', headers },
      (incoming) => {
        const chunks: Buffer[] = [];
        incoming.on('data', (chunk: Buffer) => chunks.push(chunk));
        incoming.on('end', () =>
          resolve({
            status: incoming.statusCode ?? 0,
            setCookies: incoming.headers['set-cookie'] ?? [],
            contentType: incoming.headers['content-type'] ?? '',
            body: Buffer.concat(chunks).toString('utf8'),
          }),
        );
      },
    );
    outgoing.on('error', reject);
    outgoing.end();
  });
}

const mintPathVariants = [
  '/api/loadtest/sessions',
  '/api/loadtest/sessions/',
  '/api/%6Coadtest/sessions',
  '/API/LOADTEST/SESSIONS',
  '//api/loadtest/sessions',
  '/api//loadtest/sessions',
  '/api/./loadtest/sessions',
  '/api/loadtest/./sessions',
  '/api/loadtest/sessions;jsessionid=x',
];

// Every variant the Gateway's PathPrefix /api/loadtest rule catches after Envoy's path normalisation
// (see build/reports/loadtest/lr-full/probe.txt). Case changes, %2F and mid-path `;` are not caught by it
// and are left to the API's own deny rules.
const gatewayNotFoundPaths = [
  ...mintPathVariants.filter((path) => !path.startsWith('/API/')),
  '/api/loadtest',
  '/api/loadtest/',
];

const runsAgainstKindGateway = (process.env.E2E_UPSTREAM ?? '').startsWith('shop-control-plane:');

test('the raw-path helper sends every mint path variant byte for byte', async () => {
  const received: string[] = [];
  const server = createServer((incoming, outgoing) => {
    received.push(incoming.url ?? '');
    outgoing.end();
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const { port } = server.address() as AddressInfo;
    for (const path of mintPathVariants) await rawPost(`127.0.0.1:${port}`, path, {});
  } finally {
    server.close();
  }

  expect(received).toEqual(mintPathVariants);
});

test('the loadtest mint endpoint is not reachable through the front door in any path variant', async () => {
  const upstream = process.env.E2E_UPSTREAM ?? '';
  for (const path of mintPathVariants) {
    const response = await rawPost(upstream, path, {
      Host: new URL(frontDoorOrigin).host,
      'Shop-Loadtest-Token': 'dummy-token',
    });

    expect(response.status, path).not.toBe(201);
    expect(response.setCookies.join('\n'), path).not.toContain('__Host-SESSION');
  }
});

test('the kind Gateway answers its own empty 404 for every loadtest path variant', async () => {
  test.skip(!runsAgainstKindGateway, 'only the kind Gateway has the /api/loadtest not-found rule');
  for (const path of gatewayNotFoundPaths) {
    const response = await rawPost(process.env.E2E_UPSTREAM ?? '', path, {
      Host: new URL(frontDoorOrigin).host,
    });

    expect(response.status, path).toBe(404);
    expect(response.body, path).toBe('');
    expect(response.contentType, path).not.toContain('problem+json');
  }
});

test('the browser sees the front door as a secure context', async ({ page }) => {
  await page.goto('/');

  expect(await page.evaluate(() => isSecureContext)).toBe(true);
  expect(new URL(page.url()).origin).toBe(frontDoorOrigin);
});

test('the management port is not routed: /actuator/health serves the web app', async ({
  request,
}) => {
  const response = await request.get('/actuator/health');

  expect(response.headers()['content-type']).toContain('text/html');
  expect(await response.text()).not.toContain('"status"');
});

test('/api/actuator/health is not exposed: an anonymous caller gets 401', async ({ request }) => {
  const response = await request.get('/api/actuator/health');

  expect(response.status()).toBe(401);
  expect(response.headers()['content-type']).toContain('application/problem+json');
});

test('a bare /api and an unknown API path reach the API and answer 401 to anonymous callers', async ({
  request,
}) => {
  for (const path of ['/api', '/api/no-such-endpoint']) {
    const response = await request.get(path);

    expect(response.headers()['content-type'], path).not.toContain('text/html');
    expect(response.status(), path).toBe(401);
  }
});

test('the API docs are not public by default', async ({ request }) => {
  const response = await request.get('/api/v3/api-docs');

  expect(response.status()).toBe(401);
});

test('/oauth2/authorization/keycloak redirects to the IdP with PKCE and the exact redirect_uri', async ({
  request,
}) => {
  const response = await request.get('/oauth2/authorization/keycloak', { maxRedirects: 0 });

  expect(response.status()).toBe(302);
  const location = new URL(response.headers()['location'] ?? '');
  expect(`${location.origin}${location.pathname}`).toBe(`${issuer}/protocol/openid-connect/auth`);
  expect(location.searchParams.get('code_challenge_method')).toBe('S256');
  expect(location.searchParams.get('state')).toBeTruthy();
  expect(location.searchParams.get('nonce')).toBeTruthy();
  expect(location.searchParams.get('redirect_uri')).toBe(
    `${frontDoorOrigin}/login/oauth2/code/keycloak`,
  );
});

test('a callback without a valid state fails to the home page with login=failed', async ({
  request,
}) => {
  const response = await request.get('/login/oauth2/code/keycloak?code=x&state=y', {
    maxRedirects: 0,
  });

  expect(response.status()).toBe(302);
  expect(response.headers()['location']).toBe(`${frontDoorOrigin}/?login=failed`);
});

test('GET /logout is not served by the web app', async ({ request }) => {
  const response = await request.get('/logout', { maxRedirects: 0 });

  expect(response.headers()['content-type'] ?? '').not.toContain('text/html');
  expect(response.status()).toBeGreaterThanOrEqual(400);
});

test('the back-channel logout path is not routed to the API', async ({ request }) => {
  const response = await request.post('/logout/connect/back-channel/keycloak', {
    form: { logout_token: 'x' },
  });

  expect(response.headers()['content-type']).toContain('text/html');
});

test('/login is not an auth path: the SPA serves it', async ({ request }) => {
  const response = await request.get('/login');

  expect(response.headers()['content-type']).toContain('text/html');
});

test('/apiary is not an API path and serves the web app', async ({ request }) => {
  const response = await request.get('/apiary');

  expect(response.headers()['content-type']).toContain('text/html');
});
