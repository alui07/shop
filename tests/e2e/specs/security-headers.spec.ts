import { expect, test } from './support/test';

const securityHeaders = {
  'content-security-policy':
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'strict-origin-when-cross-origin',
  'x-frame-options': 'DENY',
  'cross-origin-opener-policy': 'same-origin',
  'permissions-policy': 'camera=(), microphone=(), geolocation=(), payment=()',
};

test('the home page sends the full security header set and revalidates', async ({ request }) => {
  const response = await request.get('/');

  expect(response.status()).toBe(200);
  expect(response.headers()).toMatchObject(securityHeaders);
  expect(response.headers()['cache-control']).toBe('no-cache');
});

test('a deep link sends the same headers as the home page', async ({ request }) => {
  const response = await request.get('/products/oak-desk');

  expect(response.status()).toBe(200);
  expect(response.headers()).toMatchObject(securityHeaders);
  expect(response.headers()['cache-control']).toBe('no-cache');
});

test('a hashed asset found on the page is cached as immutable with the full header set', async ({
  page,
  request,
}) => {
  await page.goto('/');
  const scriptPath = await page.locator('script[src^="/assets/"]').first().getAttribute('src');
  expect(scriptPath).not.toBeNull();

  const response = await request.get(scriptPath ?? '');

  expect(response.status()).toBe(200);
  expect(response.headers()).toMatchObject(securityHeaders);
  expect(response.headers()['cache-control']).toBe('public, max-age=31536000, immutable');
  expect(response.headers()['content-type']).toContain('javascript');
});

test('a missing asset is a 404 with security headers and is not cached as immutable', async ({
  request,
}) => {
  const response = await request.get('/assets/missing-0000.js');

  expect(response.status()).toBe(404);
  expect(response.headers()).toMatchObject(securityHeaders);
  expect(response.headers()['cache-control'] ?? '').not.toContain('immutable');
});

test('the catalog API sends nosniff and no-cache', async ({ request }) => {
  const response = await request.get('/api/products');

  expect(response.status()).toBe(200);
  expect(response.headers()['x-content-type-options']).toBe('nosniff');
  expect(response.headers()['cache-control']).toBe('no-cache');
  expect(response.headers()['content-type']).toContain('application/json');
});
