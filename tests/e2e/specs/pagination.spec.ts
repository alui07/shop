import { expect, test } from '@playwright/test';
import { openCatalog, pagination, productCards, productNames } from './support/catalog';

test('paging forward and back updates the URL and the products', async ({ page }) => {
  await openCatalog(page);
  const firstPageNames = await productNames(page);
  const nav = pagination(page);
  await expect(nav.getByText('Previous', { exact: true })).toHaveAttribute('aria-disabled', 'true');
  await expect(nav.getByRole('link', { name: 'Previous' })).toHaveCount(0);

  await nav.getByRole('link', { name: 'Next', exact: true }).click();

  await expect(page).toHaveURL(/\/\?page=2$/);
  await expect(nav.locator('[aria-current="page"]')).toHaveText('2');
  await expect(productCards(page)).toHaveCount(12);
  const secondPageNames = await productNames(page);
  expect(secondPageNames.filter((name) => firstPageNames.includes(name))).toEqual([]);

  await nav.getByRole('link', { name: 'Previous' }).click();

  await expect(page).toHaveURL(/\/$/);
  await expect(nav.locator('[aria-current="page"]')).toHaveText('1');
  expect(await productNames(page)).toEqual(firstPageNames);
});

test('a numbered page link jumps to that page', async ({ page }) => {
  await openCatalog(page);

  await pagination(page).getByRole('link', { name: '3', exact: true }).click();

  await expect(page).toHaveURL(/\/\?page=3$/);
  await expect(pagination(page).locator('[aria-current="page"]')).toHaveText('3');
});

test('the last page has 12 products and no next link', async ({ page }) => {
  await openCatalog(page, '?page=5');

  await expect(productCards(page)).toHaveCount(12);
  await expect(pagination(page).getByRole('link', { name: 'Next' })).toHaveCount(0);
  await expect(pagination(page).getByText('Next', { exact: true })).toHaveAttribute(
    'aria-disabled',
    'true',
  );
});

test('a page past the end offers a way back to the first page', async ({ page }) => {
  await openCatalog(page, '?page=6');

  await expect(page.getByText('No products on this page.')).toBeVisible();
  await expect(productCards(page)).toHaveCount(0);

  await page.getByRole('link', { name: 'Back to first page' }).click();

  await expect(page).toHaveURL(/\/$/);
  await expect(productCards(page)).toHaveCount(12);
});
