import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { expect, test } from './support/test';
import { openCatalog, productCards } from './support/catalog';

const wcagTags = ['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'];

async function violationsOn(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(wcagTags).analyze();
  return results.violations;
}

test('the catalog with results has no accessibility violations', async ({ page }) => {
  await openCatalog(page);
  await expect(productCards(page)).toHaveCount(12);

  expect(await violationsOn(page)).toEqual([]);
});

test('the empty catalog result has no accessibility violations', async ({ page }) => {
  await openCatalog(page, '?q=zzzzqx');
  await expect(page.getByText('No products match')).toBeVisible();

  expect(await violationsOn(page)).toEqual([]);
});

test('a product page has no accessibility violations', async ({ page }) => {
  await page.goto('/products/oak-desk');
  await expect(page.getByRole('heading', { level: 1, name: 'Oak Desk' })).toBeVisible();

  expect(await violationsOn(page)).toEqual([]);
});

test('product not found has no accessibility violations', async ({ page }) => {
  await page.goto('/products/no-such-product');
  await expect(page.getByRole('heading', { level: 1, name: 'Product not found' })).toBeVisible();

  expect(await violationsOn(page)).toEqual([]);
});

test('the healthy status page has no accessibility violations', async ({ page }) => {
  await page.goto('/status');
  await expect(page.locator('dd').nth(0)).toHaveText('ok');

  expect(await violationsOn(page)).toEqual([]);
});

test('the status page API unavailable state has no accessibility violations', async ({ page }) => {
  await page.route('**/api/status', (route) => route.abort());
  await page.goto('/status');
  await expect(page.getByRole('alert')).toHaveText('API unavailable');

  expect(await violationsOn(page)).toEqual([]);
});
