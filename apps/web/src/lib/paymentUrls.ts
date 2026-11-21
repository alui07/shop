const MAX_URL_LENGTH = 2000;
const PAYMENT_PAGE_PATH = /^\/fake-psp\/([A-Za-z0-9_-]+)$/;
const ORDER_PATH = /^\/orders\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;

/** The first capture group of the pattern when the URL is same-origin, plain and matches; otherwise null. */
function captureFromSameOrigin(value: string, pattern: RegExp): string | null {
  if (value.length > MAX_URL_LENGTH) return null;
  let url: URL;
  try {
    url = new URL(value, window.location.origin);
  } catch {
    return null;
  }
  if (url.origin !== window.location.origin || url.search !== '' || url.hash !== '') return null;
  return pattern.exec(url.pathname)?.[1] ?? null;
}

/** The token of a provider payment page URL returned by the API, or null when it is anything else. */
export function paymentTokenFrom(redirectUrl: string): string | null {
  return captureFromSameOrigin(redirectUrl, PAYMENT_PAGE_PATH);
}

/** The order id of a return URL returned by the provider, or null when it is anything else. */
export function orderIdFrom(returnUrl: string): string | null {
  return captureFromSameOrigin(returnUrl, ORDER_PATH);
}
