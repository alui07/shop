import type { Page } from '@playwright/test';
import { apiFetch, csrfTokenOf } from './auth';
import { expect } from './test';

export const e2eSlugPrefix = 'e2e-';
export const e2eDescription = 'Temporary product made by an end-to-end test.';

export interface AdminProduct {
  id: number;
  slug: string;
  name: string;
  active: boolean;
  version: number;
  stock: { onHand: number; reserved: number; version: number };
}

interface AdminProductPage {
  items: AdminProduct[];
  page: number;
  totalPages: number;
}

export interface E2eProductNames {
  slug: string;
  name: string;
}

// The name avoids every word the public specs search for; the slug is unique per run and worker.
export function uniqueProductNames(): E2eProductNames {
  const token = `${Date.now()}-${Math.floor(Math.random() * 0x10000)
    .toString(16)
    .padStart(4, '0')}`;
  return { slug: `${e2eSlugPrefix}${token}`, name: `E2E Quokka ${token}` };
}

export async function listAdminProducts(page: Page, query: string): Promise<AdminProduct[]> {
  const items: AdminProduct[] = [];
  for (let pageNumber = 0; ; pageNumber += 1) {
    const response = await apiFetch(
      page,
      `/api/admin/products?q=${encodeURIComponent(query)}&size=100&page=${pageNumber}`,
    );
    expect(response.status).toBe(200);
    const body = JSON.parse(response.body) as AdminProductPage;
    items.push(...body.items);
    if (body.page + 1 >= body.totalPages) return items;
  }
}

export async function findAdminProductBySlug(page: Page, slug: string): Promise<AdminProduct> {
  const found = (await listAdminProducts(page, slug)).find((item) => item.slug === slug);
  expect(found, `admin product ${slug}`).toBeDefined();
  return found as AdminProduct;
}

// Deactivates through the API, whatever the product's other fields hold, using its current version.
export async function deactivateProduct(page: Page, slug: string): Promise<void> {
  const product = await findAdminProductBySlug(page, slug);
  if (!product.active) return;
  const detail = await apiFetch(page, `/api/admin/products/${product.id}`);
  expect(detail.status).toBe(200);
  const current = JSON.parse(detail.body) as {
    name: string;
    description: string;
    price: { amountMinor: number };
    category: { slug: string };
    version: number;
  };
  const response = await apiFetch(page, `/api/admin/products/${product.id}`, {
    method: 'PUT',
    csrfToken: await csrfTokenOf(page),
    json: {
      name: current.name,
      description: current.description,
      priceMinor: current.price.amountMinor,
      categorySlug: current.category.slug,
      active: false,
      version: current.version,
    },
  });
  expect(response.status, response.body).toBe(200);
}
