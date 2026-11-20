import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { signIn } from './support/auth';
import { findAdminProductBySlug } from './support/admin';
import {
  collectBrowserDiagnostics,
  isFailedLoadOf,
  isInsecureOriginNotice,
  settle,
} from './support/csp';
import { expect, test } from './support/test';

const wcagTags = ['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'];

async function violationsOn(page: Page) {
  return (await new AxeBuilder({ page }).withTags(wcagTags).analyze()).violations;
}

const visitedPaths = ['/', '/admin'];

for (const role of ['customer', 'admin'] as const) {
  // The collectors are registered after sign-in, so Keycloak's own pages are not counted:
  // only pages of the app origin are.
  test(`signed-in pages as ${role} have no CSP violations`, async ({ page }) => {
    await signIn(page, role);
    const diagnostics = await collectBrowserDiagnostics(page);

    for (const path of visitedPaths) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    }
    await settle(page);

    expect(diagnostics.cspViolations).toEqual([]);
    expect(diagnostics.pageErrors).toEqual([]);
    // A customer on /admin makes the API answer 403 to the admin list, which Chromium logs.
    const unexpectedErrors = diagnostics.consoleErrors.filter(
      (error) =>
        !isInsecureOriginNotice(error) &&
        !isFailedLoadOf(error, 403, '/api/admin/') &&
        !error.text.startsWith('ApiError: API request failed with status 403'),
    );
    expect(unexpectedErrors).toEqual([]);
  });

  test(`signed-in pages as ${role} have no accessibility violations`, async ({ page }) => {
    await signIn(page, role);
    expect(await violationsOn(page)).toEqual([]);

    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await settle(page);
    expect(await violationsOn(page)).toEqual([]);
  });
}

test('the anonymous admin page has no accessibility violations', async ({ page }) => {
  await page.goto('/admin');
  await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();

  expect(await violationsOn(page)).toEqual([]);
});

// The admin write pages. Read-only: nothing is saved, and no seed product is edited.
async function openAdminWritePages(page: Page): Promise<string[]> {
  const oakDesk = await findAdminProductBySlug(page, 'oak-desk');
  const paths = ['/admin/products/new', '/admin/audit', `/admin/products/${oakDesk.id}`];
  for (const path of paths) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await settle(page);
  }
  return paths;
}

async function showSlugValidationError(page: Page): Promise<void> {
  await page.goto('/admin/products/new');
  await page.getByLabel('Slug').fill('Bad Slug');
  await page.getByRole('button', { name: 'Create product' }).click();
  await expect(page.getByLabel('Slug')).toHaveAttribute('aria-invalid', 'true');
  await settle(page);
}

test('the admin write pages have no CSP violations', async ({ page }) => {
  await signIn(page, 'admin');
  const diagnostics = await collectBrowserDiagnostics(page);

  await openAdminWritePages(page);
  await showSlugValidationError(page);

  expect(diagnostics.cspViolations).toEqual([]);
  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleErrors.filter((error) => !isInsecureOriginNotice(error))).toEqual([]);
});

test('the admin write pages have no accessibility violations', async ({ page }) => {
  await signIn(page, 'admin');
  const oakDesk = await findAdminProductBySlug(page, 'oak-desk');

  for (const path of ['/admin/products/new', '/admin/audit', `/admin/products/${oakDesk.id}`]) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await settle(page);
    expect(await violationsOn(page), path).toEqual([]);
  }

  await showSlugValidationError(page);
  expect(await violationsOn(page)).toEqual([]);
});
