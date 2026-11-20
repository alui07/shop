import type { components } from '@/api/schema';

type OrderStatus = components['schemas']['Order']['status'];

export const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};

/** An unpaid order whose hold has run out is expired, even before the server has written that. */
export function effectiveStatus(status: OrderStatus, holdEnded: boolean): OrderStatus {
  return status === 'PENDING_PAYMENT' && holdEnded ? 'EXPIRED' : status;
}
