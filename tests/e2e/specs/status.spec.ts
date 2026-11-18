import { expect, test } from './support/test';

test('the status page shows the status and version served by the API', async ({
  page,
  request,
}) => {
  const response = await request.get('/api/status');
  expect(response.ok()).toBe(true);
  const { version } = (await response.json()) as { version: string };

  await page.goto('/status');

  await expect(page.getByRole('heading', { level: 1, name: 'API status' })).toBeVisible();
  await expect(page).toHaveTitle('API status | Shop');
  await expect(page.getByRole('main').getByText('Status', { exact: true })).toBeVisible();
  await expect(page.locator('dd').nth(0)).toHaveText('ok');
  await expect(page.locator('dd').nth(1)).toHaveText(version);
});
