import { useMutation, useQueryClient } from '@tanstack/react-query';
import { cancelOrder, orderQuery } from '@/api/orders';
import type { ProblemError } from '@/api/problemError';
import type { components } from '@/api/schema';
import { Button } from '@/components/ui/button';
import { useHasPassed } from '@/hooks/useHasPassed';
import { formatDateTime } from '@/lib/dateTime';
import { failureMessage } from '@/lib/failureMessage';
import { effectiveStatus, PAYMENT_STATUS_LABELS, STATUS_LABELS } from '@/lib/orderStatus';
import { formatPrice } from '@/lib/price';
import { PayNowButton } from './PayNowButton';

type Order = components['schemas']['Order'];

const CANCEL_REFUSED_MESSAGE = 'This order can no longer be cancelled.';

export function OrderDetails({ order, confirming }: { order: Order; confirming: boolean }) {
  const queryClient = useQueryClient();
  const holdEnded = useHasPassed(order.expiresAt);
  const status = effectiveStatus(order.status, holdEnded);
  const awaitingPayment = status === 'PENDING_PAYMENT' && !confirming;

  const cancel = useMutation({
    mutationFn: () => cancelOrder(order.id),
    onSuccess: (cancelled) => {
      queryClient.setQueryData(orderQuery(order.id).queryKey, cancelled);
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: orderQuery(order.id).queryKey }),
  });
  const cancelError = cancel.error as ProblemError | null;

  return (
    <>
      {confirming ? (
        <p role="status">Payment is being confirmed.</p>
      ) : (
        <p>
          Status: <strong>{STATUS_LABELS[status]}</strong>
        </p>
      )}
      {order.paymentStatus && (
        <p className="text-sm">Payment: {PAYMENT_STATUS_LABELS[order.paymentStatus]}</p>
      )}
      {order.latePayment && <p className="text-sm">Paid after the reservation ended</p>}
      <p className="text-sm">
        Placed <time dateTime={order.createdAt}>{formatDateTime(order.createdAt)}</time>
      </p>
      {awaitingPayment && (
        <p className="text-sm">
          Reserved until <time dateTime={order.expiresAt}>{formatDateTime(order.expiresAt)}</time>
        </p>
      )}
      <ul>
        {order.lines.map((line) => (
          <li key={line.productSlug} className="flex justify-between gap-4 border-b py-2">
            <span>
              {line.name} × {line.quantity}
            </span>
            <div className="text-right">
              <p>{formatPrice(line.lineTotal)}</p>
              <p className="text-sm">{formatPrice(line.unitPrice)} each</p>
            </div>
          </li>
        ))}
      </ul>
      <p className="text-lg font-semibold">Total {formatPrice(order.total)}</p>
      {cancel.isSuccess && <p role="status">Order cancelled</p>}
      {cancelError && (
        <p role="alert">
          {cancelError.status === 409
            ? CANCEL_REFUSED_MESSAGE
            : failureMessage(cancelError, 'The order could not be cancelled. Try again.')}
        </p>
      )}
      {awaitingPayment && (
        <div className="flex flex-wrap gap-2">
          <PayNowButton orderId={order.id} />
          <Button variant="outline" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
            Cancel order
          </Button>
        </div>
      )}
    </>
  );
}
