import { expect, test } from '@playwright/test';

test('GET /api/status responds with JSON through the gateway', async ({ request }) => {
  const response = await request.get('/api/status');

  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toContain('application/json');
  expect(await response.json()).toMatchObject({ status: 'ok' });
});
