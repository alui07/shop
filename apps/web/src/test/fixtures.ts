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

type Me = components['schemas']['Me'];
type AdminProduct = components['schemas']['AdminProduct'];
type AdminProductPage = components['schemas']['AdminProductPage'];

export const anonymousMe: Me = {
  authenticated: false,
  email: null,
  name: null,
  roles: [],
  subject: null,
};
export const customerMe: Me = {
  authenticated: true,
  email: 'casey@example.test',
  name: 'Casey Customer',
  roles: ['customer'],
  subject: 'subject-customer',
};
export const adminMe: Me = {
  authenticated: true,
  email: 'ada@example.test',
  name: 'Ada Admin',
  roles: ['customer', 'admin'],
  subject: 'subject-admin',
};

export function adminProduct(id: number, name: string, active = true): AdminProduct {
  return {
    id,
    slug: name.toLowerCase().replaceAll(' ', '-'),
    name,
    description: `${name} description`,
    price: { amountMinor: 12999, currency: 'EUR' },
    category: chairs,
    active,
    version: 3,
    stock: { onHand: 5, reserved: 2, version: 7 },
  };
}

export function adminProductPage(
  items: AdminProduct[],
  { page = 0, totalPages = 1, totalItems = items.length } = {},
): AdminProductPage {
  return { items, page, size: 20, totalItems, totalPages };
}

type AuditEvent = components['schemas']['AuditEvent'];
type AuditPage = components['schemas']['AuditPage'];

export function auditEvent(id: number, overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id,
    occurredAt: '2026-11-18T10:30:15Z',
    actorSubject: 'subject-admin',
    actorName: 'Ada Admin',
    action: 'product.updated',
    entityType: 'product',
    entityId: 7,
    traceId: null,
    changes: [{ field: 'name', from: 'Oak chair', to: 'Oak chair 2' }],
    ...overrides,
  };
}

export function auditPage(
  items: AuditEvent[],
  { page = 0, totalPages = 1, totalItems = items.length } = {},
): AuditPage {
  return { items, page, size: 20, totalItems, totalPages };
}
