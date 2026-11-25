import { apiFetch, csrfTokenOf } from './support/auth';
import { registerAndVerify } from './support/registration';
import { expect, test } from './support/test';

// The account is registered here and used by no other spec, so exhausting its cart-write budget cannot affect them.
// Exact counts are covered by RateLimitIT; this proves the wiring end to end, whichever of the API or Envoy limits first.

const rateLimitedType = 'tag:shop.dev,2026:rate-limited';
const slug = 'oak-desk';
const maxWrites = 120;
const maxClickAttempts = 5;
const minSuccessesBeforeLimit = 55;

test('a flood of cart writes from one account is answered with 429, Retry-After and a readable message', async ({
  browser,
}) => {
  const shopper = await registerAndVerify(browser);
  try {
    const { page } = shopper;
    const csrfToken = await csrfTokenOf(page);
    await page.goto(`/products/${slug}`);
    let successes = 0;
    let limited: Awaited<ReturnType<typeof apiFetch>> | undefined;

    for (let attempt = 0; attempt < maxWrites && !limited; attempt += 1) {
      const response = await apiFetch(page, `/api/cart/lines/${slug}`, {
        method: 'PUT',
        csrfToken,
        json: { quantity: 1 },
      });
      if (response.status === 429) {
        limited = response;
      } else {
        expect(response.status, response.body).toBe(200);
        successes += 1;
      }
    }

    expect(limited, `no 429 within ${maxWrites} writes`).toBeDefined();
    const refused = limited as NonNullable<typeof limited>;
    expect(successes).toBeGreaterThanOrEqual(minSuccessesBeforeLimit);
    expect(refused.contentType).toContain('application/problem+json');
    expect(JSON.parse(refused.body)).toMatchObject({ type: rateLimitedType, status: 429 });
    expect(refused.headers['retry-after']).toMatch(/^\d+$/);
    const retryAfter = Number(refused.headers['retry-after']);
    expect(retryAfter).toBeGreaterThanOrEqual(1);

    // The bucket refills greedily (one token a second), so the first click can land after a refill and succeed.
    // Then the bucket is emptied again and the click retried.
    const alert = page.getByRole('alert');
    for (let attempt = 0; attempt < maxClickAttempts; attempt += 1) {
      await page.getByRole('button', { name: 'Add to cart' }).click();
      await alert.or(page.getByRole('status').filter({ hasText: 'Added to cart' })).waitFor();
      if (await alert.isVisible()) break;
      for (let write = 0; write < maxWrites; write += 1) {
        const response = await apiFetch(page, `/api/cart/lines/${slug}`, {
          method: 'PUT',
          csrfToken,
          json: { quantity: 1 },
        });
        if (response.status === 429) break;
      }
    }
    await expect(alert).toHaveText(/^Too many attempts\. Try again in (\d+ seconds?|a moment)\.$/);
    // A valid Retry-After was seen above, so the message names a number of seconds, never "a moment".
    const shown = Number(/in (\d+) seconds?\./.exec((await alert.textContent()) ?? '')?.[1]);
    expect(shown).toBeGreaterThanOrEqual(1);
    // The click's 429 may come from Envoy, whose override sends a constant 60 (C31), rather than from the API.
    expect(shown).toBeLessThanOrEqual(Math.max(retryAfter, 60));
  } finally {
    await shopper.context.close();
  }
});
