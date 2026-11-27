import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { addToCartLine, cartQuery } from '@/api/cart';
import type { ProblemError } from '@/api/problemError';
import { Button } from '@/components/ui/button';
import { LabeledSelect } from './LabeledSelect';
import {
  ADD_RETRY_DELAY_MS,
  addFailureMessage,
  MAX_QUANTITY,
  MOST_ALLOWED_TEXT,
  shouldRetryAddition,
} from './addToCartRules';

interface Addition {
  key: string;
  quantity: number;
}

export function AddToCart({ slug }: { slug: string }) {
  const queryClient = useQueryClient();
  const { data: cart } = useQuery(cartQuery);
  const [quantity, setQuantity] = useState('1');
  const mostAllowedRef = useRef<HTMLParagraphElement>(null);
  const addFilledLine = useRef(false);

  const inCart = cart?.lines.find((line) => line.productSlug === slug)?.quantity ?? 0;
  const maxAddable = MAX_QUANTITY - inCart;
  const chosenQuantity = Math.min(Number(quantity), maxAddable);
  const options = Array.from({ length: maxAddable }, (_, index) => ({
    value: String(index + 1),
    label: String(index + 1),
  }));

  const add = useMutation({
    mutationFn: ({ key, quantity: amount }: Addition) => addToCartLine(slug, amount, key),
    retry: (failureCount, error) => shouldRetryAddition(failureCount, error as ProblemError),
    retryDelay: ADD_RETRY_DELAY_MS,
    onSuccess: (updated) => {
      addFilledLine.current =
        updated.lines.find((line) => line.productSlug === slug)?.quantity === MAX_QUANTITY;
      queryClient.setQueryData(cartQuery.queryKey, updated);
    },
    onError: () => queryClient.invalidateQueries({ queryKey: cartQuery.queryKey }),
  });

  const lineFilled = maxAddable === 0;
  useEffect(() => {
    if (lineFilled && addFilledLine.current) {
      addFilledLine.current = false;
      mostAllowedRef.current?.focus();
    }
  }, [lineFilled]);

  return (
    <div className="space-y-3">
      {inCart > 0 && <p>In your cart: {inCart}</p>}
      {lineFilled ? (
        <p role="status" tabIndex={-1} ref={mostAllowedRef}>
          {MOST_ALLOWED_TEXT}
        </p>
      ) : (
        <div className="flex items-end gap-3">
          <LabeledSelect
            label="Quantity"
            value={String(chosenQuantity)}
            options={options}
            onChange={setQuantity}
          />
          <Button
            disabled={add.isPending}
            onClick={() => add.mutate({ key: crypto.randomUUID(), quantity: chosenQuantity })}
          >
            Add to cart
          </Button>
        </div>
      )}
      {add.isSuccess && <p role="status">Added to cart</p>}
      {add.error && !lineFilled && (
        <p role="alert">{addFailureMessage(add.error as ProblemError)}</p>
      )}
    </div>
  );
}
