import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/catalog';
import { cancelOrder, orderQuery } from '@/api/orders';
import type { ProblemError } from '@/api/problemError';
import type { components } from '@/api/schema';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { useHasPassed } from '@/hooks/useHasPassed';
import { formatDateTime } from '@/lib/dateTime';
import { failureMessage } from '@/lib/failureMessage';
import { effectiveStatus, STATUS_LABELS } from '@/lib/orderStatus';
import { formatPrice } from '@/lib/price';
import { customerGate } from './customerGate';
import { LoadingMessage } from './LoadingMessage';
import { NotFoundPanel } from './NotFoundPanel';

type Order = components['schemas']['Order'];

const CANCEL_REFUSED_MESSAGE = 'This order can no longer be cancelled.';

function OrderDetails({ order }: { order: Order }) {
  const queryClient = useQueryClient();
  const holdEnded = useHasPassed(order.expiresAt);
  const status = effectiveStatus(order.status, holdEnded);
  const awaitingPayment = status === 'PENDING_PAYMENT';

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
      <p>
        Status: <strong>{STATUS_LABELS[status]}</strong>
      </p>
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
        <Button variant="outline" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
          Cancel order
        </Button>
      )}
    </>
  );
}

export function OrderView({ id }: { id: string }) {
  useDocumentTitle('Order');
  const { data, error, isPending } = useQuery(orderQuery(id));

  const gate = customerGate(error);
  if (gate) return gate;
  if (error instanceof ApiError && (error.status === 404 || error.status === 400)) {
    return <NotFoundPanel title="Order not found" message="This order does not exist." />;
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Order</h1>
      {isPending && <LoadingMessage text="Loading order" />}
      {error && <p role="alert">The order could not be loaded.</p>}
      {data && <OrderDetails order={data} />}
    </div>
  );
}
