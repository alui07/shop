import { expect, test } from '@playwright/test';

// The outage is simulated in the browser: page.route aborts /api/status requests.
// The API container keeps running; nothing is stopped on the server side.
test('the page reports an API outage and recovers without a reload', async ({ page }) => {
  await page.route('**/api/status', (route) => route.abort());

  await page.goto('/');
  await expect(page.getByRole('alert')).toHaveText('API unavailable');

  await page.unroute('**/api/status');

  await expect(page.locator('dd').nth(0)).toHaveText('ok', { timeout: 15_000 });
  await expect(page.getByRole('alert')).toHaveCount(0);
});
