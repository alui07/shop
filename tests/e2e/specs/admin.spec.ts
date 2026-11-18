import { signIn } from './support/auth';
import { expect, test } from './support/test';

// Read-only in slice 3a; the write flows arrive in slice 3b.
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

test('a customer reaches /admin but sees no product data', async ({ page }) => {
  await signIn(page, 'customer', '/admin');

  await expect(page.getByText(/do not have access/i)).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});
