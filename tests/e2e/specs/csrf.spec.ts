import { apiFetch, csrfTokenOf, fetchMe, signIn } from './support/auth';
import { expect, test } from './support/test';

const problemJson = 'application/problem+json';

test('an anonymous unsafe request without a CSRF token is 403, not 401', async ({ request }) => {
  const logout = await request.post('/logout');
  const adminWrite = await request.post('/api/admin/products', { data: {} });

  for (const response of [logout, adminWrite]) {
    expect(response.status()).toBe(403);
    expect(response.headers()['content-type']).toContain(problemJson);
    expect(await response.json()).toMatchObject({ status: 403 });
  }
});

test('an anonymous admin read is 401 problem+json', async ({ request }) => {
  const response = await request.get('/api/admin/products');

  expect(response.status()).toBe(401);
  expect(response.headers()['content-type']).toContain(problemJson);
});

test('a customer is 403 on the admin API and an admin is 200', async ({ page, browser }) => {
  await signIn(page, 'customer');
  const denied = await apiFetch(page, '/api/admin/products');
  expect(denied.status).toBe(403);
  expect(denied.contentType).toContain(problemJson);

  const adminContext = await browser.newContext();
  try {
    const adminPage = await adminContext.newPage();
    await signIn(adminPage, 'admin');
    const allowed = await apiFetch(adminPage, '/api/admin/products?q=oak');
    expect(allowed.status).toBe(200);
    const body = JSON.parse(allowed.body) as { items: { slug: string }[] };
    expect(body.items.map((item) => item.slug)).toContain('oak-desk');
  } finally {
    await adminContext.close();
  }
});

test('a signed-in unsafe request needs a valid CSRF token', async ({ page }) => {
  await signIn(page, 'customer');

  const missing = await apiFetch(page, '/logout', { method: 'POST' });
  expect(missing.status).toBe(403);
  expect(missing.contentType).toContain(problemJson);
  expect(JSON.parse(missing.body)).toMatchObject({ detail: 'CSRF token missing or invalid' });

  const wrong = await apiFetch(page, '/logout', { method: 'POST', csrfToken: 'not-the-token' });
  expect(wrong.status).toBe(403);

  const adminWrite = await apiFetch(page, '/api/admin/products', { method: 'POST' });
  expect(adminWrite.status).toBe(403);
  expect(JSON.parse(adminWrite.body)).toMatchObject({ detail: 'CSRF token missing or invalid' });

  expect((await fetchMe(page)).authenticated).toBe(true);
});

test('sign-out with the CSRF token answers the Keycloak logout URL and ends the session', async ({
  page,
}) => {
  await signIn(page, 'customer');

  const response = await apiFetch(page, '/logout', {
    method: 'POST',
    csrfToken: await csrfTokenOf(page),
  });

  expect(response.status).toBe(200);
  expect(response.contentType).toContain('application/json');
  const { redirectUrl } = JSON.parse(response.body) as { redirectUrl: string };
  const target = new URL(redirectUrl);
  expect(`${target.origin}${target.pathname}`).toBe(
    'http://localhost:8089/realms/shop/protocol/openid-connect/logout',
  );
  expect(target.searchParams.get('id_token_hint')).toBeTruthy();
  expect(target.searchParams.get('post_logout_redirect_uri')).toBe('http://localhost:8088/');
  expect(target.searchParams.get('client_id')).toBe('shop-api');
  expect((await fetchMe(page)).authenticated).toBe(false);
});
