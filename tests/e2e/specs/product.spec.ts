import { expect, test } from '@playwright/test';
import { openCatalog, productCards } from './support/catalog';

test('a product page opens from a card and the browser back button restores the search', async ({
  page,
}) => {
  await openCatalog(page, '?q=kettle');

  await productCards(page).getByRole('link', { name: 'Copper Kettle' }).click();

  await expect(page).toHaveURL(/\/products\/copper-kettle$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Copper Kettle' })).toBeVisible();
  await expect(page).toHaveTitle('Copper Kettle | Shop');
  await expect(page.getByText('€59.00')).toBeVisible();
  await expect(page.getByText('Kitchen', { exact: true })).toBeVisible();
  await expect(page.getByText('Stovetop kettle with a stay-cool handle')).toBeVisible();

  await page.goBack();

  await expect(page).toHaveURL(/\/\?q=kettle$/);
  await expect(page.getByRole('searchbox', { name: 'Search products' })).toHaveValue('kettle');
  await expect(productCards(page)).toHaveCount(1);
});

test('the back to products link returns to the catalog', async ({ page }) => {
  await page.goto('/products/oak-desk');
  await expect(page.getByRole('heading', { level: 1, name: 'Oak Desk' })).toBeVisible();

  await page.getByRole('link', { name: 'Back to products' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();
});

test('an unknown product shows product not found', async ({ page }) => {
  await page.goto('/products/no-such-product');

  await expect(page.getByRole('heading', { level: 1, name: 'Product not found' })).toBeVisible();
  await expect(page).toHaveTitle('Product not found | Shop');
  await expect(page.getByRole('link', { name: 'Back to products' })).toBeVisible();
});

test('an unknown route shows page not found', async ({ page }) => {
  const response = await page.goto('/some/client/route');

  expect(response?.status()).toBe(200);
  await expect(page.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
  await expect(page).toHaveTitle('Page not found | Shop');
});
