import type { Page } from '@playwright/test';
import { apiFetch, csrfTokenOf, type Role } from './auth';
import { expect } from './test';

interface CartBody {
  lines: { productSlug: string }[];
}

interface OrderPageBody {
  items: { id: string; status: string }[];
  totalPages: number;
}

// Leaves the signed-in shopper with an empty cart and no order awaiting payment, so specs that
// buy never see stock or the pending-order cap held by an earlier run. The page must be signed in.
export async function resetShopper(page: Page, role: Role): Promise<void> {
  const csrfToken = await csrfTokenOf(page);

  const cart = await apiFetch(page, '/api/cart');
  expect(cart.status, `${role} cart`).toBe(200);
  for (const line of (JSON.parse(cart.body) as CartBody).lines) {
    const removed = await apiFetch(page, `/api/cart/lines/${line.productSlug}`, {
      method: 'DELETE',
      csrfToken,
    });
    expect(removed.status, `${role} remove ${line.productSlug}`).toBe(200);
  }

  for (const orderId of await pendingOrderIds(page)) {
    const cancelled = await apiFetch(page, `/api/orders/${orderId}/cancel`, {
      method: 'POST',
      csrfToken,
    });
    // 409: the hold ran out meanwhile and the order is already expired.
    expect([200, 409], `${role} cancel ${orderId}`).toContain(cancelled.status);
  }
}

async function pendingOrderIds(page: Page): Promise<string[]> {
  const ids: string[] = [];
  for (let pageNumber = 0; ; pageNumber += 1) {
    const response = await apiFetch(page, `/api/orders?size=50&page=${pageNumber}`);
    expect(response.status).toBe(200);
    const body = JSON.parse(response.body) as OrderPageBody;
    ids.push(...body.items.filter((o) => o.status === 'PENDING_PAYMENT').map((o) => o.id));
    if (pageNumber + 1 >= body.totalPages) return ids;
  }
}
