import { expect, frontDoorOrigin, issuer, test } from './support/test';

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
