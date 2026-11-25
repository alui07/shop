import type { Page } from '@playwright/test';
import { apiFetch, csrfTokenOf, signIn } from './support/auth';
import {
  deactivateProduct,
  e2eDescription,
  findAdminProductBySlug,
  uniqueProductNames,
} from './support/admin';
import { expect, test } from './support/test';

// Runs in its own project after every public spec (see playwright.config.ts): while the product is
// active the public catalog holds one product more than the count-asserting specs expect.

async function fillProductForm(
  page: Page,
  values: { slug?: string; name: string; price: string },
): Promise<void> {
  if (values.slug !== undefined) await page.getByLabel('Slug').fill(values.slug);
  await page.getByLabel('Name').fill(values.name);
  await page.getByLabel('Description').fill(e2eDescription);
  await page.getByLabel('Price (EUR)').fill(values.price);
  await page.getByLabel('Category').selectOption({ label: 'Garden' });
}

// A public read after an admin write can hit a pod whose catalog cache is cleared a moment later by the pub/sub message.
async function expectAfterReload(page: Page, assertion: () => Promise<void>): Promise<void> {
  await expect(async () => {
    await page.reload();
    await assertion();
  }).toPass({ timeout: 5_000 });
}

async function openEditPage(page: Page, id: number): Promise<void> {
  await page.goto(`/admin/products/${id}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Edit product' })).toBeVisible();
}

test('an admin creates, edits, stocks and deactivates a product, and the audit log follows', async ({
  page,
  browser,
}) => {
  test.setTimeout(180_000);
  const { slug, name } = uniqueProductNames();
  await signIn(page, 'admin', '/admin/products/new');
  await expect(page.getByRole('heading', { level: 1, name: 'New product' })).toBeVisible();
  let created = false;

  try {
    await test.step('the form rejects a bad slug before sending anything', async () => {
      const writes: string[] = [];
      page.on('request', (request) => {
        if (request.method() === 'POST' && request.url().includes('/api/admin/')) {
          writes.push(request.url());
        }
      });
      await fillProductForm(page, { slug: 'Bad Slug', name, price: '12.34' });
      await page.getByRole('button', { name: 'Create product' }).click();

      await expect(page.getByLabel('Slug')).toHaveAttribute('aria-invalid', 'true');
      expect(writes).toEqual([]);
    });

    await test.step('the server refuses a slug that exists and shows it under Slug', async () => {
      await page.getByLabel('Slug').fill('oak-desk');
      await page.getByRole('button', { name: 'Create product' }).click();

      await expect(page.getByLabel('Slug')).toHaveAttribute('aria-invalid', 'true');
      await expect(page).toHaveURL(/\/admin\/products\/new$/);
    });

    await test.step('creating an inactive product opens its edit page', async () => {
      await page.getByLabel('Slug').fill(slug);
      await expect(page.getByLabel('Active')).not.toBeChecked();
      await page.getByRole('button', { name: 'Create product' }).click();

      await expect(page).toHaveURL(/\/admin\/products\/\d+$/);
      created = true;
      await expect(page.getByRole('heading', { level: 1, name: 'Edit product' })).toBeVisible();
      await expect(page.getByText('Product saved')).toBeVisible();
    });

    const product = await findAdminProductBySlug(page, slug);
    expect(product.active).toBe(false);
    expect(product.stock.onHand).toBe(0);

    await test.step('two saves in a row on the same page both succeed', async () => {
      await page.getByLabel('Active').check();
      await page.getByLabel('Price (EUR)').fill('15.00');
      await page.getByRole('button', { name: 'Save changes' }).click();
      await expect(page.getByText('Product saved')).toBeVisible();
      await expect.poll(async () => (await findAdminProductBySlug(page, slug)).version).toBe(1);
      await expect(page.getByRole('button', { name: 'Save changes' })).toBeEnabled();

      await page.getByLabel('Price (EUR)').fill('16.50');
      await page.getByRole('button', { name: 'Save changes' }).click();
      await expect(page.getByText('Product saved')).toBeVisible();
      await expect(page.getByLabel('Price (EUR)')).toHaveValue(/^16\.50?$/);
      await expect.poll(async () => (await findAdminProductBySlug(page, slug)).version).toBe(2);
    });

    await test.step('the public catalog shows the active product', async () => {
      await page.goto(`/products/${slug}`);
      await expectAfterReload(page, async () => {
        await expect(page.getByRole('heading', { level: 1, name })).toBeVisible({ timeout: 1_000 });
        await expect(page.getByText('€16.50')).toBeVisible({ timeout: 1_000 });
      });

      await page.goto('/?q=Quokka');
      await expectAfterReload(page, async () => {
        await expect(page.getByRole('article').filter({ hasText: name })).toBeVisible({
          timeout: 1_000,
        });
      });
    });

    await test.step('setting stock shows in the admin table', async () => {
      await openEditPage(page, product.id);
      await page.getByLabel('On hand').fill('7');
      await page.getByRole('button', { name: 'Save stock' }).click();
      await expect(page.getByText('Stock saved')).toBeVisible();

      await expect
        .poll(async () => (await findAdminProductBySlug(page, slug)).stock.onHand)
        .toBe(7);
      await page.goto(`/admin?q=${slug}`);
      const row = page.getByRole('row', { name: new RegExp(name) });
      await expect(row).toBeVisible();
      await expect(row).toContainText('Active');
      await expect(row.getByRole('cell', { name: '7', exact: true })).toBeVisible();
    });

    await test.step('a second page saving a stale version gets the conflict alert and recovers', async () => {
      await openEditPage(page, product.id);
      const other = await page.context().newPage();
      await openEditPage(other, product.id);

      await page.getByLabel('Price (EUR)').fill('17.00');
      await page.getByRole('button', { name: 'Save changes' }).click();
      await expect(page.getByText('Product saved')).toBeVisible();

      await other.getByLabel('Price (EUR)').fill('18.00');
      await other.getByRole('button', { name: 'Save changes' }).click();
      await expect(other.getByRole('alert')).toContainText(
        'This product was changed by someone else.',
      );
      await other.getByRole('button', { name: 'Reload' }).click();
      await expect(other.getByLabel('Price (EUR)')).toHaveValue(/^17\.00?$/);
      await other.close();
    });

    await test.step('deactivating removes the product from the public catalog', async () => {
      await openEditPage(page, product.id);
      await page.getByLabel('Active').uncheck();
      await page.getByRole('button', { name: 'Save changes' }).click();
      await expect(page.getByText('Product saved')).toBeVisible();

      await page.goto(`/products/${slug}`);
      await expectAfterReload(page, async () => {
        await expect(
          page.getByRole('heading', { level: 1, name: 'Product not found' }),
        ).toBeVisible({ timeout: 1_000 });
      });
    });

    await test.step('the audit log lists the changes for this product', async () => {
      await page.goto('/admin/audit');
      await expect(page.getByRole('heading', { level: 1, name: 'Audit log' })).toBeVisible();
      await expect(page.getByRole('columnheader', { name: 'Trace (unverified)' })).toBeVisible();

      const rows = page
        .getByRole('row')
        .filter({ has: page.locator(`a[href="/admin/products/${product.id}"]`) });
      await expect(rows.filter({ hasText: 'product.created' }).first()).toBeVisible();
      await expect(rows.filter({ hasText: 'product.updated' }).first()).toBeVisible();
      await expect(rows.filter({ hasText: 'stock.set' }).first()).toBeVisible();
      await expect(rows.filter({ hasText: 'Test Admin' }).first()).toBeVisible();
    });

    await test.step('a write without the CSRF header is refused', async () => {
      const response = await apiFetch(page, `/api/admin/products/${product.id}`, {
        method: 'PUT',
        json: {},
      });
      expect(response.status).toBe(403);
      expect(response.contentType).toContain('application/problem+json');
    });

    await test.step('a customer cannot write', async () => {
      const customerContext = await browser.newContext();
      try {
        const customerPage = await customerContext.newPage();
        await signIn(customerPage, 'customer');
        const response = await apiFetch(customerPage, `/api/admin/products/${product.id}`, {
          method: 'PUT',
          csrfToken: await csrfTokenOf(customerPage),
          json: {},
        });
        expect(response.status).toBe(403);
        expect(response.contentType).toContain('application/problem+json');
      } finally {
        await customerContext.close();
      }
    });
  } finally {
    // A failing cleanup must not hide the error that ended the test; the next run's setup deactivates leftovers.
    if (created) {
      await deactivateProduct(page, slug).catch((error: unknown) => {
        console.error(`cleanup of ${slug} failed`, error);
      });
    }
  }
});
