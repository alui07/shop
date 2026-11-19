import { signIn } from './support/auth';
import { expect, test } from './support/test';

// Read-only checks; the write flows are in admin-writes.spec.ts, which runs last.
test('an admin sees the product table and can search it', async ({ page }) => {
  await signIn(page, 'admin', '/admin');

  await expect(page.getByRole('heading', { level: 1, name: 'Admin products' })).toBeVisible();
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.getByRole('row')).not.toHaveCount(1);

  await page.getByRole('search').getByRole('searchbox').fill('oak');
  await page.getByRole('search').getByRole('button', { name: 'Search' }).click();
  await expect(page).toHaveURL(/\/admin\?q=oak/);
  await expect(page.getByRole('row', { name: /Oak Desk/ })).toBeVisible();
  await expect(page.getByRole('row', { name: /Copper Kettle/ })).toHaveCount(0);
});

test('an admin reaches the new product form and the audit log from the navigation', async ({
  page,
}) => {
  await signIn(page, 'admin', '/admin');

  await page.getByRole('link', { name: 'Audit log' }).first().click();
  await expect(page.getByRole('heading', { level: 1, name: 'Audit log' })).toBeVisible();

  await page.goto('/admin');
  await page.getByRole('link', { name: 'New product' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'New product' })).toBeVisible();
  await expect(page.getByLabel('Slug')).toBeVisible();
});

test('the admin product list shows stock and links to the edit page of a seed product', async ({
  page,
}) => {
  await signIn(page, 'admin', '/admin?q=oak');

  await expect(page.getByRole('columnheader', { name: 'Stock' })).toBeVisible();
  await page.getByRole('link', { name: 'Oak Desk' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Edit product' })).toBeVisible();
  await expect(page.getByLabel('Slug')).toHaveValue('oak-desk');
});

test('a customer reaches /admin but sees no product data', async ({ page }) => {
  await signIn(page, 'customer', '/admin');

  await expect(page.getByText(/do not have access/i)).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});
