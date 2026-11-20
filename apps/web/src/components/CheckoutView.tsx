import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useCallback, useState } from 'react';
import { cartQuery } from '@/api/cart';
import { orderQuery } from '@/api/orders';
import type { ProblemError } from '@/api/problemError';
import type { components } from '@/api/schema';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { failureMessage } from '@/lib/failureMessage';
import { formatPrice } from '@/lib/price';
import { placeOrder, type PlaceOrderResult } from '@/lib/placeOrder';
import { customerGate } from './customerGate';
import { LoadingMessage } from './LoadingMessage';

type Cart = components['schemas']['Cart'];

const CART_CHANGED_MESSAGE = 'Your cart changed. Check it and try again.';
const RESERVATION_LIMIT_MESSAGE =
  "You can't reserve more of this product until your unpaid orders are paid or cancelled.";
const TOO_MANY_PENDING_MESSAGE = 'You have too many orders awaiting payment.';
const OUT_OF_STOCK_MESSAGE = 'Not enough stock for the products below.';
const PLACE_FAILED_MESSAGE = 'The order could not be placed. Try again.';

interface Problems {
  alert: string;
  productNames: string[];
}

function problemsOf(result: Exclude<PlaceOrderResult, { kind: 'placed' }>, cart: Cart): Problems {
  const namesOf = (slugs: string[]) =>
    slugs.map((slug) => cart.lines.find((line) => line.productSlug === slug)?.name ?? slug);
  switch (result.kind) {
    case 'cart-changed':
      return { alert: CART_CHANGED_MESSAGE, productNames: [] };
    case 'too-many-pending-orders':
      return { alert: TOO_MANY_PENDING_MESSAGE, productNames: [] };
    case 'out-of-stock':
      return { alert: OUT_OF_STOCK_MESSAGE, productNames: namesOf(result.slugs) };
    case 'reservation-limit':
      return { alert: RESERVATION_LIMIT_MESSAGE, productNames: namesOf(result.slugs) };
    case 'failed':
      return {
        alert: failureMessage(result.error as ProblemError, PLACE_FAILED_MESSAGE),
        productNames: [],
      };
  }
}

export function CheckoutView() {
  useDocumentTitle('Checkout');
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { data: cart, error, isPending } = useQuery(cartQuery);
  const [placing, setPlacing] = useState(false);
  const [problems, setProblems] = useState<Problems>();

  const focusWhenShown = useCallback((element: HTMLDivElement | null) => element?.focus(), []);

  const gate = customerGate(error);
  if (gate) return gate;

  const handlePlace = async (current: Cart) => {
    setPlacing(true);
    setProblems(undefined);
    const result = await placeOrder(current);
    if (result.kind === 'placed') {
      queryClient.setQueryData(orderQuery(result.order.id).queryKey, result.order);
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
      void queryClient.invalidateQueries({ queryKey: cartQuery.queryKey });
      await navigate({ to: '/orders/$id', params: { id: result.order.id } });
      return;
    }
    setProblems(problemsOf(result, current));
    if (result.kind !== 'failed')
      void queryClient.invalidateQueries({ queryKey: cartQuery.queryKey });
    setPlacing(false);
  };

  const hasUnavailableLine = cart?.lines.some((line) => !line.available) ?? false;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Checkout</h1>
      {isPending && <LoadingMessage text="Loading cart" />}
      {error && <p role="alert">The cart could not be loaded.</p>}
      {problems && (
        <div
          role="alert"
          tabIndex={-1}
          ref={focusWhenShown}
          className="space-y-2 rounded-md border border-destructive p-3 text-sm"
        >
          <p>{problems.alert}</p>
          {problems.productNames.length > 0 && (
            <ul className="list-disc pl-5">
              {problems.productNames.map((name) => (
                <li key={name}>{name}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      {cart && cart.lines.length === 0 && <p>Your cart is empty</p>}
      {cart && cart.lines.length > 0 && (
        <>
          <ul>
            {cart.lines.map((line) => (
              <li key={line.productSlug} className="flex justify-between gap-4 border-b py-2">
                <span>
                  {line.name} × {line.quantity}
                </span>
                <span>{formatPrice(line.lineTotal)}</span>
              </li>
            ))}
          </ul>
          {hasUnavailableLine && (
            <p>Some products are not available in this quantity. Check your cart.</p>
          )}
          <div className="flex items-center justify-between gap-4">
            <p className="text-lg font-semibold">Total {formatPrice(cart.total)}</p>
            <Button disabled={placing || hasUnavailableLine} onClick={() => void handlePlace(cart)}>
              Place order
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
