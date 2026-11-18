import { expect, test } from './support/test';

test('the browser sees the front door as a secure context', async ({ page }) => {
  await page.goto('/');

  expect(await page.evaluate(() => isSecureContext)).toBe(true);
  expect(new URL(page.url()).origin).toBe('http://localhost:8088');
});

test('the management port is not routed: /actuator/health serves the web app', async ({
  request,
}) => {
  const response = await request.get('/actuator/health');

  expect(response.headers()['content-type']).toContain('text/html');
  expect(await response.text()).not.toContain('"status"');
});

test('/api/actuator/health is not found', async ({ request }) => {
  const response = await request.get('/api/actuator/health');

  expect(response.status()).toBe(404);
});

test('a bare /api reaches the API, not the web app', async ({ request }) => {
  const response = await request.get('/api');

  expect(response.headers()['content-type']).not.toContain('text/html');
  expect(response.status()).toBe(404);
});

test('/apiary is not an API path and serves the web app', async ({ request }) => {
  const response = await request.get('/apiary');

  expect(response.headers()['content-type']).toContain('text/html');
});
