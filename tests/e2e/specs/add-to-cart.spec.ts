import type { Page } from '@playwright/test';
import { apiFetch, csrfTokenOf } from './support/auth';
import { registerAndVerify } from './support/registration';
import { expect, test } from './support/test';

// Each test registers its own account, so no other spec shares its cart or its cart-write budget.
// copper-kettle is read by product.spec and catalog.spec and written by no other spec.

const slug = 'copper-kettle';
const additionsPath = `/api/cart/lines/${slug}/additions`;
const maxQuantity = 10;
const lineQuantityLimitType = 'tag:shop.dev,2026:line-quantity-limit';
const keyReusedType = 'tag:shop.dev,2026:idempotency-key-reused';
const mostAllowedText = 'You have 10 of this product in your cart, the most allowed.';

async function addThroughUi(page: Page, quantity: number): Promise<void> {
  await page.getByLabel('Quantity').selectOption(String(quantity));
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Added to cart' })).toBeVisible();
}

function addition(page: Page, csrfToken: string | null, key: string, quantity: number) {
  return apiFetch(page, additionsPath, {
    method: 'POST',
    csrfToken,
    headers: { 'Idempotency-Key': key },
    json: { quantity },
  });
}

async function quantityInCart(page: Page): Promise<number> {
  const response = await apiFetch(page, '/api/cart');
  expect(response.status, response.body).toBe(200);
  const cart = JSON.parse(response.body) as { lines: { productSlug: string; quantity: number }[] };
  return cart.lines.find((line) => line.productSlug === slug)?.quantity ?? 0;
}

test('Add to cart adds to the line and stops offering more at the limit', async ({ browser }) => {
  const shopper = await registerAndVerify(browser);
  try {
    const { page } = shopper;
    await page.goto(`/products/${slug}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Copper Kettle' })).toBeVisible();

    await addThroughUi(page, 3);
    await expect(page.getByRole('link', { name: 'Cart (3)' })).toBeVisible();
    await expect(page.getByText('In your cart: 3')).toBeVisible();

    await addThroughUi(page, 4);
    await expect(page.getByRole('link', { name: 'Cart (7)' })).toBeVisible();
    await expect(page.getByText('In your cart: 7')).toBeVisible();
    await expect(page.getByLabel('Quantity').getByRole('option')).toHaveText(['1', '2', '3']);

    await addThroughUi(page, 3);
    await expect(page.getByRole('link', { name: 'Cart (10)' })).toBeVisible();
    await expect(page.getByText('Added to cart')).toBeVisible();
    await expect(page.getByText(mostAllowedText)).toHaveCount(1);
    await expect(page.getByRole('status').filter({ hasText: mostAllowedText })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add to cart' })).toHaveCount(0);
    expect(await quantityInCart(page)).toBe(maxQuantity);
  } finally {
    await shopper.context.close();
  }
});

test('the additions API replays a repeated key, refuses a changed request, the limit and a missing CSRF header', async ({
  browser,
}) => {
  const shopper = await registerAndVerify(browser);
  try {
    const { page } = shopper;
    await page.goto(`/products/${slug}`);
    const csrfToken = await csrfTokenOf(page);
    const key = crypto.randomUUID();

    const first = await addition(page, csrfToken, key, 2);
    expect(first.status, first.body).toBe(200);
    expect(first.headers['idempotency-replayed']).toBeUndefined();

    const replay = await addition(page, csrfToken, key, 2);
    expect(replay.status, replay.body).toBe(200);
    expect(replay.headers['idempotency-replayed']).toBe('true');
    expect(await quantityInCart(page)).toBe(2);

    const reused = await addition(page, csrfToken, key, 3);
    expect(reused.status, reused.body).toBe(422);
    expect(reused.contentType).toContain('application/problem+json');
    expect(JSON.parse(reused.body)).toMatchObject({ type: keyReusedType, status: 422 });
    expect(await quantityInCart(page)).toBe(2);

    const filled = await addition(page, csrfToken, crypto.randomUUID(), maxQuantity - 2);
    expect(filled.status, filled.body).toBe(200);

    const refused = await addition(page, csrfToken, crypto.randomUUID(), 1);
    expect(refused.status, refused.body).toBe(409);
    expect(refused.contentType).toContain('application/problem+json');
    expect(JSON.parse(refused.body)).toMatchObject({
      type: lineQuantityLimitType,
      status: 409,
      quantityInCart: maxQuantity,
    });
    expect(await quantityInCart(page)).toBe(maxQuantity);

    const withoutCsrf = await addition(page, null, crypto.randomUUID(), 1);
    expect(withoutCsrf.status).toBe(403);
    expect(await quantityInCart(page)).toBe(maxQuantity);
  } finally {
    await shopper.context.close();
  }
});
