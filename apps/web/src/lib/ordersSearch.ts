import { pageField } from '@/lib/catalogSearch';
import { z } from '@/lib/zod';

const ordersSearchSchema = z.object({ page: pageField });

export type OrdersSearch = z.output<typeof ordersSearchSchema>;

export function parseOrdersSearch(raw: Record<string, unknown>): OrdersSearch {
  return ordersSearchSchema.parse(raw);
}
