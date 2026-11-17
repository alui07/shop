import { expect, test } from '@playwright/test';

test('home page shows the status and version served by the API', async ({ page, request }) => {
  const response = await request.get('/api/status');
  expect(response.ok()).toBe(true);
  const { version } = (await response.json()) as { version: string };

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Shop' })).toBeVisible();
  await expect(page.getByText('Status', { exact: true })).toBeVisible();
  await expect(page.locator('dd').nth(0)).toHaveText('ok');
  await expect(page.locator('dd').nth(1)).toHaveText(version);
});

test('a deep link is served by the single page app', async ({ page }) => {
  const response = await page.goto('/some/client/route');

  expect(response?.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Shop' })).toBeVisible();
  await expect(page.locator('dd').nth(0)).toHaveText('ok');
});
