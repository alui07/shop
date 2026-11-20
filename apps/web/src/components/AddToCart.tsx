import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { cartQuery, putCartLine } from '@/api/cart';
import type { ProblemError } from '@/api/problemError';
import { Button } from '@/components/ui/button';
import { failureMessage } from '@/lib/failureMessage';
import { LabeledSelect } from './LabeledSelect';

const MAX_QUANTITY = 10;
const QUANTITY_OPTIONS = Array.from({ length: MAX_QUANTITY }, (_, index) => ({
  value: String(index + 1),
  label: String(index + 1),
}));
const CART_FULL_TYPE = 'tag:shop.dev,2026:cart-full';

function addFailure(error: ProblemError): string {
  if (error.status === 404) return 'This product is no longer available.';
  if (error.problem.type === CART_FULL_TYPE) return 'Your cart is full. Remove an item first.';
  return failureMessage(error, 'The product could not be added. Try again.');
}

export function AddToCart({ slug }: { slug: string }) {
  const queryClient = useQueryClient();
  const [quantity, setQuantity] = useState('1');
  const add = useMutation({
    mutationFn: () => putCartLine(slug, Number(quantity)),
    onSuccess: (cart) => queryClient.setQueryData(cartQuery.queryKey, cart),
  });

  return (
    <div className="space-y-3">
      <div className="flex items-end gap-3">
        <LabeledSelect
          label="Quantity"
          value={quantity}
          options={QUANTITY_OPTIONS}
          onChange={setQuantity}
        />
        <Button disabled={add.isPending} onClick={() => add.mutate()}>
          Add to cart
        </Button>
      </div>
      {add.isSuccess && <p role="status">Added to cart</p>}
      {add.error && <p role="alert">{addFailure(add.error as ProblemError)}</p>}
    </div>
  );
}
