import { expect, test } from '@playwright/test';
import { collectBrowserDiagnostics, settle, type ConsoleError } from './support/csp';
import { simulateCatalogOutage } from './support/catalog';

// Chromium ignores COOP on plain http outside localhost; the test stack is http://gateway:8080.
const isInsecureOriginNotice = (error: ConsoleError) =>
  error.text.startsWith('The Cross-Origin-Opener-Policy header has been ignored');

const isFailedLoadOf = (error: ConsoleError, status: number, urlPart: string) =>
  error.text.includes(`status of ${status}`) && error.url.includes(urlPart);

test('the collector sees a real violation, so zero elsewhere is meaningful', async ({ page }) => {
  const diagnostics = await collectBrowserDiagnostics(page);
  await page.goto('/status');

  await page.evaluate(() => fetch('https://example.invalid/blocked').catch(() => undefined));

  await expect
    .poll(() => diagnostics.cspViolations.filter((v) => v.directive === 'connect-src'))
    .toHaveLength(1);
});

test('no page of the main flow violates the Content Security Policy', async ({ page }) => {
  const diagnostics = await collectBrowserDiagnostics(page);

  const visits: { name: string; path: string; heading: string }[] = [
    { name: 'catalog', path: '/', heading: 'Products' },
    {
      name: 'catalog with results for a search',
      path: '/?q=oak&sort=price_desc',
      heading: 'Products',
    },
    { name: 'product page', path: '/products/oak-desk', heading: 'Oak Desk' },
    { name: 'product not found', path: '/products/no-such-product', heading: 'Product not found' },
    { name: 'page not found', path: '/no/such/page', heading: 'Page not found' },
    { name: 'status', path: '/status', heading: 'API status' },
  ];
  for (const visit of visits) {
    await test.step(visit.name, async () => {
      await page.goto(visit.path);
      await expect(page.getByRole('heading', { level: 1, name: visit.heading })).toBeVisible();
    });
  }
  await page.getByRole('link', { name: 'Shop', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();
  await settle(page);

  expect(diagnostics.cspViolations).toEqual([]);
  expect(diagnostics.pageErrors).toEqual([]);
  // The product not found visit makes the API answer 404, which Chromium logs as a console error.
  const unexpectedErrors = diagnostics.consoleErrors.filter(
    (error) =>
      !isInsecureOriginNotice(error) &&
      !isFailedLoadOf(error, 404, '/api/products/no-such-product'),
  );
  expect(unexpectedErrors).toEqual([]);
});

// The failure below is simulated with page.route (HTTP 503), not a real API fault. Chromium logs
// the failed load and the router logs the loader error; both are expected, CSP violations are not.
test('the simulated catalog error state has no CSP violations', async ({ page }) => {
  const diagnostics = await collectBrowserDiagnostics(page);
  await simulateCatalogOutage(page);

  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Something went wrong' })).toBeVisible();
  await settle(page);

  expect(diagnostics.cspViolations).toEqual([]);
  expect(diagnostics.pageErrors).toEqual([]);
  const unexpectedErrors = diagnostics.consoleErrors.filter(
    (error) =>
      !isInsecureOriginNotice(error) &&
      !isFailedLoadOf(error, 503, '/api/products') &&
      !error.text.startsWith('ApiError: API request failed with status 503'),
  );
  expect(unexpectedErrors).toEqual([]);
});
