import type { Page } from '@playwright/test';
import { apiFetch, csrfTokenOf } from './auth';
import { e2eDescription, uniqueProductNames } from './admin';
import { expect } from './test';

export const stockedUnitPriceMinor = 1250;

export interface StockedProduct {
  slug: string;
  name: string;
}

export function uniqueWombat(): StockedProduct {
  const names = uniqueProductNames();
  return { slug: names.slug, name: names.name.replace('Quokka', 'Wombat') };
}

// An admin page creates an active product with its own stock, so specs that buy never use seed stock.
export async function createStockedProduct(
  adminPage: Page,
  product: StockedProduct,
  onHand: number,
): Promise<void> {
  const csrfToken = await csrfTokenOf(adminPage);
  const created = await apiFetch(adminPage, '/api/admin/products', {
    method: 'POST',
    csrfToken,
    json: {
      slug: product.slug,
      name: product.name,
      description: e2eDescription,
      priceMinor: stockedUnitPriceMinor,
      categorySlug: 'garden',
      active: true,
    },
  });
  expect(created.status, created.body).toBe(201);
  const { id, stock } = JSON.parse(created.body) as { id: number; stock: { version: number } };
  const stocked = await apiFetch(adminPage, `/api/admin/products/${id}/stock`, {
    method: 'PUT',
    csrfToken,
    json: { onHand, version: stock.version },
  });
  expect(stocked.status, stocked.body).toBe(200);
}
