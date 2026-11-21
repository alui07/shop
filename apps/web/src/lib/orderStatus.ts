import type { components } from '@/api/schema';

type OrderStatus = components['schemas']['Order']['status'];

export const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  PAID: 'Paid',
  REFUND_PENDING: 'Refund pending',
  REFUNDED: 'Refunded',
};

/** An unpaid order whose hold has run out is expired, even before the server has written that. */
export function effectiveStatus(status: OrderStatus, holdEnded: boolean): OrderStatus {
  return status === 'PENDING_PAYMENT' && holdEnded ? 'EXPIRED' : status;
}

type PaymentStatus = NonNullable<components['schemas']['Order']['paymentStatus']>;

export const PAYMENT_STATUS_LABELS: Record<PaymentStatus, string> = {
  CREATED: 'Started',
  SUCCEEDED: 'Received',
  FAILED: 'Failed',
  REFUND_PENDING: 'Refund pending',
  REFUNDED: 'Refunded',
};

/** After paying, the server may still show the order unpaid or expired until the webhook is processed. */
export function awaitsPaymentConfirmation(order: {
  status: OrderStatus;
  paymentStatus: PaymentStatus | null;
}): boolean {
  const unsettled = order.status === 'PENDING_PAYMENT' || order.status === 'EXPIRED';
  return unsettled && order.paymentStatus !== 'FAILED';
}
