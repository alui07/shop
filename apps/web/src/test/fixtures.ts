import type { components } from '@/api/schema';

type Category = components['schemas']['Category'];
type ProductSummary = components['schemas']['ProductSummary'];
type ProductDetail = components['schemas']['ProductDetail'];
type ProductPage = components['schemas']['ProductPage'];

export const chairs: Category = { slug: 'chairs', name: 'Chairs' };
export const lamps: Category = { slug: 'lamps', name: 'Lamps' };
export const categories: Category[] = [chairs, lamps];

export function productSummary(slug: string, name: string, amountMinor = 1250): ProductSummary {
  return { slug, name, price: { amountMinor, currency: 'EUR' }, category: chairs };
}

export const oakChair = productSummary('oak-chair', 'Oak chair', 12999);
export const pineChair = productSummary('pine-chair', 'Pine chair', 4950);

export const oakChairDetail: ProductDetail = {
  ...oakChair,
  description: 'A sturdy chair made of solid oak.',
};

export function productPage(
  items: ProductSummary[],
  { page = 0, totalPages = 1, totalItems = items.length } = {},
): ProductPage {
  return { items, page, size: 12, totalItems, totalPages };
}
