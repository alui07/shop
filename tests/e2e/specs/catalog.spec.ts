import { expect, test } from './support/test';
import {
  openCatalog,
  pagination,
  productCards,
  productNames,
  productPrices,
} from './support/catalog';

test('the catalog lists the first page of seeded products with prices', async ({ page }) => {
  await openCatalog(page);

  await expect(page).toHaveTitle('Products | Shop');
  await expect(productCards(page)).toHaveCount(12);
  const firstCard = productCards(page).first();
  await expect(firstCard.getByRole('link', { name: 'Bamboo Bath Mat' })).toHaveAttribute(
    'href',
    '/products/bamboo-bath-mat',
  );
  await expect(firstCard).toContainText('Bath');
  await expect(firstCard).toContainText('€29.90');
  await expect(pagination(page).locator('[aria-current="page"]')).toHaveText('1');
});

test('the header navigation links to the catalog and to the status page', async ({ page }) => {
  await openCatalog(page);
  const mainNav = page.getByRole('navigation', { name: 'Main' });

  await mainNav.getByRole('link', { name: 'Status' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'API status' })).toBeVisible();

  await mainNav.getByRole('link', { name: 'Products' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
});

test.describe('search', () => {
  test('narrows the results and puts the query in the URL', async ({ page }) => {
    await openCatalog(page);

    await page.getByRole('searchbox', { name: 'Search products' }).fill('kettle');
    await page.getByRole('button', { name: 'Search' }).click();

    await expect(page).toHaveURL(/\/\?q=kettle$/);
    await expect(productCards(page)).toHaveCount(1);
    await expect(productCards(page).first()).toContainText('Copper Kettle');
    await expect(pagination(page)).toHaveCount(0);
  });

  test('submits with Enter and returns to the first page', async ({ page }) => {
    await openCatalog(page, '?page=3');

    const searchbox = page.getByRole('searchbox', { name: 'Search products' });
    await searchbox.fill('lantern');
    await searchbox.press('Enter');

    await expect(page).toHaveURL(/\/\?q=lantern$/);
    await expect(productCards(page)).toHaveCount(1);
    await expect(productCards(page).first()).toContainText('Candle Lantern');
  });

  test('shows the empty state when nothing matches', async ({ page }) => {
    await openCatalog(page, '?q=zzzzqx');

    await expect(page.getByText('No products match')).toBeVisible();
    await expect(productCards(page)).toHaveCount(0);
  });

  test('matches whole words only, not prefixes', async ({ page }) => {
    await openCatalog(page, '?q=kett');

    await expect(page.getByText('No products match')).toBeVisible();
  });

  test('clearing the search removes the query from the URL', async ({ page }) => {
    await openCatalog(page, '?q=kettle');

    await page.getByRole('searchbox', { name: 'Search products' }).fill('');
    await page.getByRole('button', { name: 'Search' }).click();

    await expect(page).toHaveURL(/\/$/);
    await expect(productCards(page)).toHaveCount(12);
  });
});

test('a deep link restores the search, category and sort controls and results', async ({
  page,
}) => {
  await openCatalog(page, '?q=oak&category=home-office&sort=price_asc');

  await expect(page.getByRole('searchbox', { name: 'Search products' })).toHaveValue('oak');
  await expect(page.getByLabel('Category')).toHaveValue('home-office');
  await expect(page.getByLabel('Sort by')).toHaveValue('price_asc');
  await expect(productCards(page)).toHaveCount(2);
  expect(await productNames(page)).toEqual(['Pen Holder', 'Oak Desk']);
});

test('the category filter shows only that category and can be cleared', async ({ page }) => {
  await openCatalog(page);

  await page.getByLabel('Category').selectOption({ label: 'Kitchen' });

  await expect(page).toHaveURL(/\/\?category=kitchen$/);
  await expect(productCards(page)).toHaveCount(10);
  await expect(productCards(page).getByText('Kitchen', { exact: true })).toHaveCount(10);
  await expect(pagination(page)).toHaveCount(0);

  await page.getByLabel('Category').selectOption({ label: 'All categories' });

  await expect(page).toHaveURL(/\/$/);
  await expect(productCards(page)).toHaveCount(12);
});

test.describe('sort', () => {
  test('price low to high lists the cheapest products first', async ({ page }) => {
    await openCatalog(page);

    await page.getByLabel('Sort by').selectOption({ label: 'Price: low to high' });

    await expect(page).toHaveURL(/\/\?sort=price_asc$/);
    await expect(productCards(page).first()).toContainText('€6.90');
    const prices = await productPrices(page);
    expect(prices).toHaveLength(12);
    expect(prices).toEqual([...prices].sort((a, b) => a - b));
  });

  test('price high to low lists the dearest products first', async ({ page }) => {
    await openCatalog(page);

    await page.getByLabel('Sort by').selectOption({ label: 'Price: high to low' });

    await expect(page).toHaveURL(/\/\?sort=price_desc$/);
    await expect(productCards(page).first()).toContainText('Oak Desk');
    await expect(productCards(page).first()).toContainText('€329.00');
    const prices = await productPrices(page);
    expect(prices).toHaveLength(12);
    expect(prices).toEqual([...prices].sort((a, b) => b - a));
  });
});
