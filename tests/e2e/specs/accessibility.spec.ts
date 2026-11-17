import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

const wcagTags = ['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'];

async function violationsOn(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(wcagTags).analyze();
  return results.violations;
}

test('the healthy home page has no accessibility violations', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('dd').nth(0)).toHaveText('ok');

  expect(await violationsOn(page)).toEqual([]);
});

test('the API unavailable state has no accessibility violations', async ({ page }) => {
  await page.route('**/api/status', (route) => route.abort());
  await page.goto('/');
  await expect(page.getByRole('alert')).toHaveText('API unavailable');

  expect(await violationsOn(page)).toEqual([]);
});
