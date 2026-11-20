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

type Cart = components['schemas']['Cart'];
type CartLine = components['schemas']['CartLine'];
type Order = components['schemas']['Order'];
type OrderPage = components['schemas']['OrderPage'];

export function cartLine(slug: string, name: string, quantity = 1, available = true): CartLine {
  const unitPrice = { amountMinor: 5000, currency: 'EUR' };
  return {
    productSlug: slug,
    name,
    unitPrice,
    quantity,
    lineTotal: { amountMinor: 5000 * quantity, currency: 'EUR' },
    available,
  };
}

export function cartOf(lines: CartLine[], version = 3): Cart {
  const itemCount = lines.reduce((sum, line) => sum + line.quantity, 0);
  const amountMinor = lines.reduce((sum, line) => sum + line.lineTotal.amountMinor, 0);
  return { version, lines, itemCount, total: { amountMinor, currency: 'EUR' } };
}

export const ORDER_ID = '11111111-1111-4111-8111-111111111111';

export function orderOf(overrides: Partial<Order> = {}): Order {
  return {
    id: ORDER_ID,
    status: 'PENDING_PAYMENT',
    createdAt: '2026-11-19T10:00:00Z',
    expiresAt: '2999-01-01T10:15:00Z',
    lines: [cartLine('oak-chair', 'Oak chair', 2)],
    total: { amountMinor: 10000, currency: 'EUR' },
    ...overrides,
  };
}

export function orderPage(
  orders: Order[],
  { page = 0, totalPages = 1, totalItems = orders.length } = {},
): OrderPage {
  return {
    items: orders.map((order) => ({
      id: order.id,
      status: order.status,
      createdAt: order.createdAt,
      expiresAt: order.expiresAt,
      itemCount: order.lines.reduce((sum, line) => sum + line.quantity, 0),
      total: order.total,
    })),
    page,
    size: 20,
    totalItems,
    totalPages,
  };
}
