import { ProblemError } from '@/api/problemError';
import { submitCheckout } from '@/api/orders';
import type { components } from '@/api/schema';
import { dropIdempotencyKey, idempotencyKeyFor } from './checkoutKey';
import { IN_PROGRESS_TYPE } from './failureMessage';

type Cart = components['schemas']['Cart'];
type Order = components['schemas']['Order'];

const TYPE_PREFIX = 'tag:shop.dev,2026:';
const CART_CHANGED_TYPES = [`${TYPE_PREFIX}cart-changed`, `${TYPE_PREFIX}price-changed`];
const OUT_OF_STOCK_TYPE = `${TYPE_PREFIX}out-of-stock`;
const RESERVATION_LIMIT_TYPE = `${TYPE_PREFIX}reservation-limit`;
const TOO_MANY_PENDING_TYPE = `${TYPE_PREFIX}too-many-pending-orders`;
const KEY_REUSED_TYPE = `${TYPE_PREFIX}idempotency-key-reused`;
const IN_PROGRESS_RETRY_DELAY_MS = 1000;

export type PlaceOrderResult =
  | { kind: 'placed'; order: Order }
  | { kind: 'cart-changed' }
  | { kind: 'out-of-stock'; slugs: string[] }
  | { kind: 'reservation-limit'; slugs: string[] }
  | { kind: 'too-many-pending-orders' }
  | { kind: 'failed'; error: ProblemError };

const sleep = (milliseconds: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, milliseconds));

async function attempt(
  key: string,
  body: { cartVersion: number; expectedTotalMinor: number },
): Promise<Order | ProblemError> {
  try {
    return await submitCheckout(key, body);
  } catch (error) {
    return error as ProblemError;
  }
}

/**
 * Places the order for this cart. The same key is sent on every retry of an attempt, including after
 * a 5xx or a network error, so a repeated request can never create a second order.
 */
export async function placeOrder(
  cart: Cart,
  wait: (milliseconds: number) => Promise<void> = sleep,
): Promise<PlaceOrderResult> {
  const key = idempotencyKeyFor(cart.version);
  const body = { cartVersion: cart.version, expectedTotalMinor: cart.total.amountMinor };

  let outcome = await attempt(key, body);
  if (outcome instanceof ProblemError && outcome.problem.type === IN_PROGRESS_TYPE) {
    await wait(IN_PROGRESS_RETRY_DELAY_MS);
    outcome = await attempt(key, body);
  }

  if (!(outcome instanceof ProblemError)) {
    dropIdempotencyKey(cart.version);
    return { kind: 'placed', order: outcome };
  }

  const { type, errors } = outcome.problem;
  const slugs = errors.map((error) => error.field);
  if (outcome.status === 409 && type !== undefined) {
    if (CART_CHANGED_TYPES.includes(type)) {
      dropIdempotencyKey(cart.version);
      return { kind: 'cart-changed' };
    }
    if (type === OUT_OF_STOCK_TYPE) return { kind: 'out-of-stock', slugs };
    if (type === RESERVATION_LIMIT_TYPE) return { kind: 'reservation-limit', slugs };
    if (type === TOO_MANY_PENDING_TYPE) return { kind: 'too-many-pending-orders' };
  }
  if (outcome.status === 422 && type === KEY_REUSED_TYPE) dropIdempotencyKey(cart.version);
  return { kind: 'failed', error: outcome };
}
