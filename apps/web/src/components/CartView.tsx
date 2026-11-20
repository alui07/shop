import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { cartQuery, putCartLine, removeCartLine } from '@/api/cart';
import type { ProblemError } from '@/api/problemError';
import type { components } from '@/api/schema';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { failureMessage } from '@/lib/failureMessage';
import { formatPrice } from '@/lib/price';
import { customerGate } from './customerGate';
import { LabeledSelect } from './LabeledSelect';
import { LoadingMessage } from './LoadingMessage';

type CartLine = components['schemas']['CartLine'];

const MAX_QUANTITY = 10;
const QUANTITY_OPTIONS = Array.from({ length: MAX_QUANTITY }, (_, index) => ({
  value: String(index + 1),
  label: String(index + 1),
}));

function CartLineRow({
  line,
  disabled,
  onQuantity,
  onRemove,
}: {
  line: CartLine;
  disabled: boolean;
  onQuantity: (quantity: number) => void;
  onRemove: () => void;
}) {
  return (
    <li className="flex flex-wrap items-end justify-between gap-4 border-b py-3">
      <div className="space-y-1">
        <p className="font-medium">{line.name}</p>
        <p className="text-sm">{formatPrice(line.unitPrice)} each</p>
        {!line.available && (
          <p className="text-sm text-destructive">Not available in this quantity</p>
        )}
      </div>
      <div className="flex items-end gap-3">
        <LabeledSelect
          label={`Quantity of ${line.name}`}
          value={String(line.quantity)}
          options={QUANTITY_OPTIONS}
          onChange={(value) => onQuantity(Number(value))}
        />
        <p className="w-24 text-right font-medium">{formatPrice(line.lineTotal)}</p>
        <Button variant="outline" size="sm" disabled={disabled} onClick={onRemove}>
          Remove
        </Button>
      </div>
    </li>
  );
}

export function CartView() {
  useDocumentTitle('Cart');
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { data: cart, error, isPending } = useQuery(cartQuery);

  const change = useMutation({
    mutationFn: ({ slug, quantity }: { slug: string; quantity: number | null }) =>
      quantity === null ? removeCartLine(slug) : putCartLine(slug, quantity),
    onSuccess: (updated) => queryClient.setQueryData(cartQuery.queryKey, updated),
    onError: () => void queryClient.invalidateQueries({ queryKey: cartQuery.queryKey }),
  });

  const gate = customerGate(error);
  if (gate) return gate;

  const canCheckout =
    cart !== undefined && cart.lines.length > 0 && cart.lines.every((line) => line.available);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Cart</h1>
      {isPending && <LoadingMessage text="Loading cart" />}
      {error && <p role="alert">The cart could not be loaded.</p>}
      {change.error && (
        <p role="alert">
          {failureMessage(
            change.error as ProblemError,
            'The cart could not be changed. Try again.',
          )}
        </p>
      )}
      {cart && cart.lines.length === 0 && <p>Your cart is empty</p>}
      {cart && cart.lines.length > 0 && (
        <ul>
          {cart.lines.map((line) => (
            <CartLineRow
              key={line.productSlug}
              line={line}
              disabled={change.isPending}
              onQuantity={(quantity) => change.mutate({ slug: line.productSlug, quantity })}
              onRemove={() => change.mutate({ slug: line.productSlug, quantity: null })}
            />
          ))}
        </ul>
      )}
      {cart && (
        <div className="flex items-center justify-between gap-4">
          <p className="text-lg font-semibold">Total {formatPrice(cart.total)}</p>
          <Button disabled={!canCheckout} onClick={() => void navigate({ to: '/checkout' })}>
            Checkout
          </Button>
        </div>
      )}
    </div>
  );
}
