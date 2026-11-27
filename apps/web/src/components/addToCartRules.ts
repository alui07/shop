import type { ProblemError } from '@/api/problemError';
import { failureMessage, IN_PROGRESS_TYPE } from '@/lib/failureMessage';

export const MAX_QUANTITY = 10;
export const ADD_RETRY_DELAY_MS = 1000;
const MAX_RETRIES = 2;
const CART_FULL_TYPE = 'tag:shop.dev,2026:cart-full';
const LINE_QUANTITY_LIMIT_TYPE = 'tag:shop.dev,2026:line-quantity-limit';

export const MOST_ALLOWED_TEXT = `You have ${MAX_QUANTITY} of this product in your cart, the most allowed.`;
const UNCONFIRMED_TEXT = 'We could not confirm the addition. Check your cart before trying again.';

/** The outcome of the request is unknown, so the addition may or may not have happened. */
function isUnconfirmed(error: ProblemError): boolean {
  return error.status === 0 || error.status >= 500 || error.problem.type === IN_PROGRESS_TYPE;
}

/** Retries reuse the same Idempotency-Key, so a repeat of an addition that went through is a replay. */
export function shouldRetryAddition(failureCount: number, error: ProblemError): boolean {
  return failureCount < MAX_RETRIES && isUnconfirmed(error);
}

function lineQuantityLimitMessage(quantityInCart: number | undefined): string {
  if (quantityInCart === undefined || quantityInCart >= MAX_QUANTITY) return MOST_ALLOWED_TEXT;
  return `You have ${quantityInCart} of this product in your cart. You can add up to ${MAX_QUANTITY - quantityInCart} more.`;
}

export function addFailureMessage(error: ProblemError): string {
  if (isUnconfirmed(error)) return UNCONFIRMED_TEXT;
  if (error.status === 404) return 'This product is no longer available.';
  if (error.problem.type === CART_FULL_TYPE) return 'Your cart is full. Remove an item first.';
  if (error.problem.type === LINE_QUANTITY_LIMIT_TYPE) {
    return lineQuantityLimitMessage(error.problem.quantityInCart);
  }
  return failureMessage(error, 'The product could not be added. Try again.');
}
