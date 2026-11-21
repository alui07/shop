export const CONFIRMATION_POLL_MS = 1000;
export const CONFIRMATION_TIMEOUT_MS = 20_000;

interface PaymentReturnState {
  fromPayment?: boolean;
}

/**
 * Router state set when the provider page sends the shopper back to the order. The router's state
 * type cannot be extended without a direct dependency on its history package, so the cast lives here.
 */
export const RETURNED_FROM_PAYMENT = { fromPayment: true } as never;

export function isReturnedFromPayment(state: unknown): boolean {
  return (state as PaymentReturnState).fromPayment === true;
}
