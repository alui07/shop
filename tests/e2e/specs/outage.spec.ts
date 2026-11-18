import { expect, test } from './support/test';
import { productCards, simulateCatalogOutage } from './support/catalog';

// Both outages are simulated in the browser with page.route. The API container keeps running;
// nothing is stopped on the server side.
test('the status page reports an API outage and recovers without a reload', async ({ page }) => {
  await page.route('**/api/status', (route) => route.abort());

  await page.goto('/status');
  await expect(page.getByRole('alert')).toHaveText('API unavailable');

  await page.unroute('**/api/status');

  await expect(page.locator('dd').nth(0)).toHaveText('ok', { timeout: 15_000 });
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('the catalog shows an error state when the product list fails and recovers on retry', async ({
  page,
}) => {
  await simulateCatalogOutage(page);

  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Something went wrong' })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveText('The shop could not load this page.');

  await page.unroute(/\/api\/products(\?|$)/);
  await page.getByRole('button', { name: 'Try again' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();
  await expect(productCards(page)).toHaveCount(12);
});
