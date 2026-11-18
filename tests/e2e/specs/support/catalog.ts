import type { Locator, Page } from '@playwright/test';
import { expect } from './test';

export function productCards(page: Page): Locator {
  return page.getByRole('article');
}

export function pagination(page: Page): Locator {
  return page.getByRole('navigation', { name: 'Pagination' });
}

export async function productNames(page: Page): Promise<string[]> {
  return productCards(page).getByRole('link').allInnerTexts();
}

export async function productPrices(page: Page): Promise<number[]> {
  const labels = await productCards(page).getByTestId('price').allInnerTexts();
  expect(labels.length).toBeGreaterThan(0);
  return labels.map((label) => Number(label.replace(/[^\d.]/g, '')));
}

export async function openCatalog(page: Page, query = ''): Promise<void> {
  await page.goto(`/${query}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();
}

export function simulateCatalogOutage(page: Page) {
  const productList = /\/api\/products(\?|$)/;
  return page.route(productList, (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'about:blank', title: 'Service Unavailable', status: 503 }),
    }),
  );
}
