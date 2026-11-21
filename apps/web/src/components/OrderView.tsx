import { useQuery } from '@tanstack/react-query';
import { useLocation } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { ApiError } from '@/api/catalog';
import { orderQuery } from '@/api/orders';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { awaitsPaymentConfirmation } from '@/lib/orderStatus';
import {
  CONFIRMATION_POLL_MS,
  CONFIRMATION_TIMEOUT_MS,
  isReturnedFromPayment,
} from '@/lib/paymentState';
import { customerGate } from './customerGate';
import { LoadingMessage } from './LoadingMessage';
import { NotFoundPanel } from './NotFoundPanel';
import { OrderDetails } from './OrderDetails';

/** True for a limited time after the provider page sent the shopper back, until the webhook lands. */
function useConfirmationWindow(): boolean {
  const fromPayment = useLocation({ select: (location) => isReturnedFromPayment(location.state) });
  const [timedOut, setTimedOut] = useState(false);

  useEffect(() => {
    if (!fromPayment) return undefined;
    const timer = setTimeout(() => setTimedOut(true), CONFIRMATION_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [fromPayment]);

  return fromPayment && !timedOut;
}

export function OrderView({ id }: { id: string }) {
  useDocumentTitle('Order');
  const inWindow = useConfirmationWindow();
  const { data, error, isPending } = useQuery({
    ...orderQuery(id),
    refetchInterval: (query) =>
      inWindow && query.state.data && awaitsPaymentConfirmation(query.state.data)
        ? CONFIRMATION_POLL_MS
        : false,
  });

  const gate = customerGate(error);
  if (gate) return gate;
  if (error instanceof ApiError && (error.status === 404 || error.status === 400)) {
    return <NotFoundPanel title="Order not found" message="This order does not exist." />;
  }

  const confirming = inWindow && data !== undefined && awaitsPaymentConfirmation(data);

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Order</h1>
      {isPending && <LoadingMessage text="Loading order" />}
      {error && <p role="alert">The order could not be loaded.</p>}
      {data && <OrderDetails order={data} confirming={confirming} />}
    </div>
  );
}
