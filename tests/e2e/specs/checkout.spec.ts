import AxeBuilder from '@axe-core/playwright';
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { apiFetch, csrfTokenOf, signIn } from './support/auth';
import { deactivateProduct, e2eDescription, uniqueProductNames } from './support/admin';
import { collectBrowserDiagnostics, isInsecureOriginNotice, settle } from './support/csp';
import { resetShopper } from './support/shopper';
import { expect, test } from './support/test';

// Runs in its own project after admin-writes (see playwright.config.ts). It buys only a product it
// creates itself, so the seeded catalog and the persistent kind database never run out of stock.

test.describe.configure({ mode: 'serial' });

const unitPriceMinor = 1250;
const wcagTags = ['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'];

interface Order {
  id: string;
  status: string;
  total: { amountMinor: number };
}

interface Cart {
  version: number;
  total: { amountMinor: number };
}

const names = uniqueProductNames();
const product = { slug: names.slug, name: names.name.replace('Quokka', 'Wombat') };
let productCreated = false;
let customerContext: BrowserContext;
let adminContext: BrowserContext;
let customer: Page;
let admin: Page;
let customerOrderId = '';

async function openSignedInPage(browser: Browser, role: 'customer' | 'admin') {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, role);
  return { context, page };
}

async function createStockedProduct(page: Page): Promise<void> {
  const csrfToken = await csrfTokenOf(page);
  const created = await apiFetch(page, '/api/admin/products', {
    method: 'POST',
    csrfToken,
    json: {
      slug: product.slug,
      name: product.name,
      description: e2eDescription,
      priceMinor: unitPriceMinor,
      categorySlug: 'garden',
      active: true,
    },
  });
  expect(created.status, created.body).toBe(201);
  productCreated = true;
  const { id, stock } = JSON.parse(created.body) as { id: number; stock: { version: number } };
  const stocked = await apiFetch(page, `/api/admin/products/${id}/stock`, {
    method: 'PUT',
    csrfToken,
    json: { onHand: 2, version: stock.version },
  });
  expect(stocked.status, stocked.body).toBe(200);
}

async function addToCartThroughUi(page: Page, quantity: number): Promise<void> {
  await page.goto(`/products/${product.slug}`);
  await expect(page.getByRole('heading', { level: 1, name: product.name })).toBeVisible();
  await page.getByLabel('Quantity').selectOption(String(quantity));
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByText('Added to cart')).toBeVisible();
}

async function placeOrderThroughUi(page: Page): Promise<void> {
  await page.getByRole('link', { name: /^Cart/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Cart' })).toBeVisible();
  await page.getByRole('button', { name: 'Checkout' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Checkout' })).toBeVisible();
  await page.getByRole('button', { name: 'Place order' }).click();
}

function orderIdFromUrl(page: Page): string {
  const match = /\/orders\/([0-9a-f-]{36})$/.exec(page.url());
  expect(match, page.url()).not.toBeNull();
  return (match as RegExpExecArray)[1] as string;
}

async function putCartLine(page: Page, quantity: number): Promise<void> {
  const response = await apiFetch(page, `/api/cart/lines/${product.slug}`, {
    method: 'PUT',
    csrfToken: await csrfTokenOf(page),
    json: { quantity },
  });
  expect(response.status, response.body).toBe(200);
}

async function currentCart(page: Page): Promise<Cart> {
  const response = await apiFetch(page, '/api/cart');
  expect(response.status).toBe(200);
  return JSON.parse(response.body) as Cart;
}

function checkoutRequest(
  page: Page,
  csrfToken: string | null,
  key: string,
  cart: Cart,
  expectedTotalMinor = cart.total.amountMinor,
) {
  return apiFetch(page, '/api/checkout', {
    method: 'POST',
    csrfToken,
    headers: { 'Idempotency-Key': key },
    json: { cartVersion: cart.version, expectedTotalMinor },
  });
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000);
  ({ context: customerContext, page: customer } = await openSignedInPage(browser, 'customer'));
  ({ context: adminContext, page: admin } = await openSignedInPage(browser, 'admin'));
  await resetShopper(customer, 'customer');
  await resetShopper(admin, 'admin');
  await createStockedProduct(admin);
});

test.afterAll(async () => {
  // Cleanup failures must not hide a test failure; the next run's admin-cleanup deactivates leftovers.
  for (const [page, role] of [
    [customer, 'customer'],
    [admin, 'admin'],
  ] as const) {
    await resetShopper(page, role).catch((error: unknown) => {
      console.error(`reset of ${role} failed`, error);
    });
  }
  if (productCreated) {
    await deactivateProduct(admin, product.slug).catch((error: unknown) => {
      console.error(`cleanup of ${product.slug} failed`, error);
    });
  }
  await customerContext?.close();
  await adminContext?.close();
});

test('an anonymous visitor is asked to sign in to buy', async ({ page }) => {
  await page.goto(`/products/${product.slug}`);
  await expect(page.getByRole('heading', { level: 1, name: product.name })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Sign in to buy' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add to cart' })).toHaveCount(0);

  const cart = await apiFetch(page, '/api/cart');
  expect(cart.status).toBe(401);
});

test('a customer adds two, places the order and sees it awaiting payment at the snapshot price', async () => {
  await addToCartThroughUi(customer, 2);
  await placeOrderThroughUi(customer);

  await expect(customer).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  customerOrderId = orderIdFromUrl(customer);
  await expect(customer.getByRole('heading', { level: 1, name: 'Order' })).toBeVisible();
  await expect(customer.getByText('Awaiting payment', { exact: true })).toBeVisible();
  await expect(customer.getByText(product.name).first()).toBeVisible();
  // The order view shows quantity and line total (2 x the snapshot price of 12.50), not the unit price.
  await expect(customer.getByRole('listitem').filter({ hasText: product.name })).toContainText(
    /× 2\s*€25\.00/,
  );
  await expect(customer.getByText('Total €25.00')).toBeVisible();
  await expect(customer.getByText(/Reserved until/)).toBeVisible();

  await customer.goto('/orders');
  await expect(customer.getByRole('heading', { level: 1, name: 'Your orders' })).toBeVisible();
  await expect(customer.locator(`a[href="/orders/${customerOrderId}"]`)).toBeVisible();
});

test('the admin, also a customer, cannot check out the same product and cannot read the order', async () => {
  await addToCartThroughUi(admin, 1);
  // The cart view already marks the line and keeps Checkout disabled, so the UI never submits it.
  await admin.getByRole('link', { name: /^Cart/ }).click();
  await expect(admin.getByRole('heading', { level: 1, name: 'Cart' })).toBeVisible();
  await expect(admin.getByText('Not available in this quantity')).toBeVisible();
  await expect(admin.getByRole('button', { name: 'Checkout' })).toBeDisabled();

  const cart = await currentCart(admin);
  const refused = await checkoutRequest(admin, await csrfTokenOf(admin), crypto.randomUUID(), cart);
  expect(refused.status).toBe(409);
  expect(refused.body).toContain('tag:shop.dev,2026:out-of-stock');
  expect(refused.body).toContain(product.slug);

  const foreign = await apiFetch(admin, `/api/orders/${customerOrderId}`);
  expect(foreign.status).toBe(404);
  const foreignCancel = await apiFetch(admin, `/api/orders/${customerOrderId}/cancel`, {
    method: 'POST',
    csrfToken: await csrfTokenOf(admin),
  });
  expect(foreignCancel.status).toBe(404);

  await resetShopper(admin, 'admin');
});

test('the customer cancels and the order shows as cancelled', async () => {
  await customer.goto(`/orders/${customerOrderId}`);
  await customer.getByRole('button', { name: 'Cancel order' }).click();
  await expect(customer.getByText('Cancelled', { exact: true })).toBeVisible();
  await expect(customer.getByRole('button', { name: 'Cancel order' })).toHaveCount(0);
});

test('the admin can order now; the key is idempotent and the API enforces CSRF', async () => {
  await addToCartThroughUi(admin, 1);
  await placeOrderThroughUi(admin);
  await expect(admin).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  await expect(admin.getByText('Awaiting payment', { exact: true })).toBeVisible();
  await admin.getByRole('button', { name: 'Cancel order' }).click();
  await expect(admin.getByText('Cancelled', { exact: true })).toBeVisible();

  await putCartLine(admin, 1);
  const cart = await currentCart(admin);
  const csrfToken = await csrfTokenOf(admin);
  const key = crypto.randomUUID();

  const withoutCsrf = await checkoutRequest(admin, null, key, cart);
  expect(withoutCsrf.status).toBe(403);

  const first = await checkoutRequest(admin, csrfToken, key, cart);
  expect(first.status, first.body).toBe(201);
  expect(first.headers['idempotency-replayed']).toBeUndefined();
  const created = JSON.parse(first.body) as Order;
  expect(created.status).toBe('PENDING_PAYMENT');
  expect(created.total.amountMinor).toBe(unitPriceMinor);

  const replay = await checkoutRequest(admin, csrfToken, key, cart);
  expect(replay.status, replay.body).toBe(201);
  expect(replay.headers['idempotency-replayed']).toBe('true');
  expect((JSON.parse(replay.body) as Order).id).toBe(created.id);

  const otherBody = await checkoutRequest(admin, csrfToken, key, cart, cart.total.amountMinor + 1);
  expect(otherBody.status).toBe(422);
  expect(otherBody.body).toContain('tag:shop.dev,2026:idempotency-key-reused');

  const cancelled = await apiFetch(admin, `/api/orders/${created.id}/cancel`, {
    method: 'POST',
    csrfToken,
  });
  expect(cancelled.status).toBe(200);
});

test.describe('cart, checkout and order pages', () => {
  test('have no CSP violations', async () => {
    await putCartLine(customer, 1);
    const diagnostics = await collectBrowserDiagnostics(customer);

    for (const path of ['/cart', '/checkout', '/orders', `/orders/${customerOrderId}`]) {
      await customer.goto(path);
      await expect(customer.getByRole('heading', { level: 1 }).first()).toBeVisible();
    }
    await settle(customer);

    expect(diagnostics.cspViolations).toEqual([]);
    expect(diagnostics.pageErrors).toEqual([]);
    expect(diagnostics.consoleErrors.filter((error) => !isInsecureOriginNotice(error))).toEqual([]);
  });

  test('have no accessibility violations', async () => {
    await putCartLine(customer, 1);
    for (const path of ['/cart', '/checkout', '/orders', `/orders/${customerOrderId}`]) {
      await customer.goto(path);
      await expect(customer.getByRole('heading', { level: 1 }).first()).toBeVisible();
      await settle(customer);
      const result = await new AxeBuilder({ page: customer }).withTags(wcagTags).analyze();
      expect(result.violations, path).toEqual([]);
    }
  });
});
