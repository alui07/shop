import { signInLink } from './support/auth';
import { expect, frontDoorOrigin, idpOrigin, issuer, test } from './support/test';

const redirectUri = `${frontDoorOrigin}/login/oauth2/code/keycloak`;
const authEndpoint = '/realms/shop/protocol/openid-connect/auth';
const pkce =
  'code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256';
const tokenEndpoint = '/realms/shop/protocol/openid-connect/token';

test('discovery advertises the public issuer on the IdP origin', async ({ idpRequest }) => {
  const response = await idpRequest.get('/realms/shop/.well-known/openid-configuration');

  expect(response.status()).toBe(200);
  const metadata = (await response.json()) as Record<string, unknown>;
  expect(metadata['issuer']).toBe(issuer);
  expect(metadata['authorization_endpoint']).toBe(`${issuer}/protocol/openid-connect/auth`);
  expect(metadata['end_session_endpoint']).toBe(`${issuer}/protocol/openid-connect/logout`);
  expect(metadata['jwks_uri']).toBe(`${issuer}/protocol/openid-connect/certs`);
  expect(metadata['code_challenge_methods_supported']).toContain('S256');
});

test('the admin console, the master realm, client registration and the management port are not routed', async ({
  idpRequest,
}) => {
  const notRouted = [
    '/',
    '/admin/',
    '/admin/master/console/',
    '/realms/master/.well-known/openid-configuration',
    '/realms/shop/clients-registrations/default',
    '/realms/shop/clients-registrations/openid-connect',
    '/health/ready',
    '/metrics',
  ];
  for (const path of notRouted) {
    const response = await idpRequest.get(path, { maxRedirects: 0 });
    expect(response.status(), path).toBe(404);
  }
  const registration = await idpRequest.post('/realms/shop/clients-registrations/default', {
    data: { clientId: 'rogue' },
  });
  expect(registration.status()).toBe(404);
});

test('the login page is served with its static resources', async ({ idpRequest }) => {
  const login = await idpRequest.get(
    `${authEndpoint}?client_id=shop-api&response_type=code&scope=openid&${pkce}&redirect_uri=${encodeURIComponent(redirectUri)}`,
  );
  expect(login.status()).toBe(200);
  const stylesheet = (await login.text()).match(/href="([^"]*\/resources\/[^"]*\.css)"/)?.[1];
  expect(stylesheet).toBeTruthy();
  const resource = await idpRequest.get(new URL(stylesheet ?? '', idpOrigin).pathname);
  expect(resource.status()).toBe(200);
});

test('an authorization request without PKCE is refused', async ({ idpRequest }) => {
  const response = await idpRequest.get(
    `${authEndpoint}?client_id=shop-api&response_type=code&scope=openid&redirect_uri=${encodeURIComponent(redirectUri)}`,
    { maxRedirects: 0 },
  );

  expect(response.status()).toBe(302);
  expect(response.headers()['location']).toContain('error=invalid_request');
});

test('an unregistered redirect_uri is refused', async ({ idpRequest }) => {
  const response = await idpRequest.get(
    `${authEndpoint}?client_id=shop-api&response_type=code&scope=openid&${pkce}&redirect_uri=${encodeURIComponent('https://evil.example/cb')}`,
    { maxRedirects: 0 },
  );

  expect(response.status()).toBe(400);
  expect(await response.text()).not.toContain('type="password"');
});

test('the implicit flow is refused', async ({ idpRequest }) => {
  const response = await idpRequest.get(
    `${authEndpoint}?client_id=shop-api&response_type=token&scope=openid&redirect_uri=${encodeURIComponent(redirectUri)}`,
    { maxRedirects: 0 },
  );

  expect(response.headers()['location'] ?? '').not.toContain('access_token');
  expect(await response.text()).not.toContain('type="password"');
});

test('the password grant (also on admin-cli), token exchange and device flow are refused', async ({
  idpRequest,
}) => {
  const grants: Record<string, string>[] = [
    {
      grant_type: 'password',
      client_id: 'shop-api',
      username: 'customer@example.test',
      password: 'x',
    },
    {
      grant_type: 'password',
      client_id: 'admin-cli',
      username: 'nobody@example.test',
      password: 'x',
    },
    {
      grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
      client_id: 'shop-api',
      subject_token: 'x',
    },
    { grant_type: 'client_credentials', client_id: 'shop-api' },
  ];
  for (const form of grants) {
    const response = await idpRequest.post(tokenEndpoint, { form });
    expect(response.status(), form['grant_type']).toBeGreaterThanOrEqual(400);
    expect(response.status(), form['grant_type']).toBeLessThan(500);
    expect(await response.json()).toHaveProperty('error');
  }

  const adminCli = await idpRequest.post(tokenEndpoint, { form: grants[1] });
  expect(((await adminCli.json()) as { error: string }).error).toBe('invalid_client');

  const device = await idpRequest.post('/realms/shop/protocol/openid-connect/auth/device', {
    form: { client_id: 'shop-api' },
  });
  expect(device.status()).toBeGreaterThanOrEqual(400);
  expect(device.status()).toBeLessThan(500);
});

test('the sign-in page offers registration', async ({ page }) => {
  await page.goto('/');
  await signInLink(page).click();
  await expect(page).toHaveURL(new RegExp(`^${idpOrigin}/realms/shop/`));
  await expect(page.getByRole('link', { name: 'Register' })).toBeVisible();
});

test('there is no password reset link and the reset-credentials form is not served', async ({
  idpRequest,
  page,
}) => {
  await page.goto('/');
  await signInLink(page).click();
  await expect(page).toHaveURL(new RegExp(`^${idpOrigin}/realms/shop/`));
  await expect(page.getByRole('link', { name: /forgot/i })).toHaveCount(0);
  expect(await page.content()).not.toContain('reset-credentials');

  const response = await idpRequest.get(
    `/realms/shop/login-actions/reset-credentials?client_id=shop-api`,
    { maxRedirects: 0 },
  );
  const body = await response.text();
  expect(response.status() === 404 || !body.includes('<form'), `status ${response.status()}`).toBe(
    true,
  );
});
