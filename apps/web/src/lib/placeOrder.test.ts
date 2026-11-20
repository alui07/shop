import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from '@/api/schema';
import { jsonResponse, stubFetch } from '@/test/fetchStub';
import { placeOrder } from './placeOrder';

type Cart = components['schemas']['Cart'];

const cart: Cart = {
  version: 4,
  itemCount: 1,
  lines: [],
  total: { amountMinor: 2500, currency: 'EUR' },
};
const order = {
  id: '11111111-1111-4111-8111-111111111111',
  status: 'PENDING_PAYMENT',
  createdAt: '2026-11-19T10:00:00Z',
  expiresAt: '2026-11-19T10:15:00Z',
  lines: [],
  total: cart.total,
};
const noWait = () => Promise.resolve();

function problem(status: number, name: string, errors: { field: string; message: string }[] = []) {
  return jsonResponse({ type: `tag:shop.dev,2026:${name}`, status, errors }, status);
}

afterEach(() => {
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe('placeOrder', () => {
  it('sends the cart version, the expected total and the stored key, then drops the key', async () => {
    const fetchMock = stubFetch({ '/api/checkout': () => jsonResponse(order, 201) });

    const result = await placeOrder(cart, noWait);

    const request = fetchMock.mock.calls[0]?.[0] as Request;
    expect(result).toEqual({ kind: 'placed', order });
    expect(request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(await request.json()).toEqual({ cartVersion: 4, expectedTotalMinor: 2500 });
    expect(sessionStorage.getItem('checkout:4')).toBeNull();
  });

  it('keeps the key after a server error and reuses it on the next attempt', async () => {
    const keys: (string | null)[] = [];
    let calls = 0;
    stubFetch({
      '/api/checkout': (_url, request) => {
        keys.push(request.headers.get('Idempotency-Key'));
        calls += 1;
        return calls === 1 ? problem(500, 'internal') : jsonResponse(order, 201);
      },
    });

    const first = await placeOrder(cart, noWait);
    const second = await placeOrder(cart, noWait);

    expect(first.kind).toBe('failed');
    expect(second.kind).toBe('placed');
    expect(keys[0]).toBe(keys[1]);
  });

  it('keeps the key after a network error', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new TypeError('offline')));

    const result = await placeOrder(cart, noWait);

    expect(result.kind).toBe('failed');
    expect(sessionStorage.getItem('checkout:4')).not.toBeNull();
  });

  it('retries once after a second with the same key while the first request is in progress', async () => {
    const keys: (string | null)[] = [];
    stubFetch({
      '/api/checkout': (_url, request) => {
        keys.push(request.headers.get('Idempotency-Key'));
        return keys.length === 1
          ? problem(409, 'idempotency-in-progress')
          : jsonResponse(order, 201);
      },
    });
    const wait = vi.fn(noWait);

    const result = await placeOrder(cart, wait);

    expect(result.kind).toBe('placed');
    expect(wait).toHaveBeenCalledWith(1000);
    expect(keys[0]).toBe(keys[1]);
  });

  it('gives up after one retry when the request is still in progress', async () => {
    const fetchMock = stubFetch({
      '/api/checkout': () => problem(409, 'idempotency-in-progress'),
    });

    const result = await placeOrder(cart, noWait);

    expect(result.kind).toBe('failed');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('waits one second with the real timer by default', async () => {
    vi.useFakeTimers();
    let calls = 0;
    stubFetch({
      '/api/checkout': () =>
        ++calls === 1 ? problem(409, 'idempotency-in-progress') : jsonResponse(order, 201),
    });

    const pending = placeOrder(cart);
    await vi.advanceTimersByTimeAsync(1000);

    expect((await pending).kind).toBe('placed');
    vi.useRealTimers();
  });

  it.each(['cart-changed', 'price-changed'])('drops the key on %s', async (type) => {
    stubFetch({ '/api/checkout': () => problem(409, type) });

    expect(await placeOrder(cart, noWait)).toEqual({ kind: 'cart-changed' });
    expect(sessionStorage.getItem('checkout:4')).toBeNull();
  });

  it.each(['out-of-stock', 'reservation-limit'] as const)(
    'lists the products of %s',
    async (type) => {
      stubFetch({
        '/api/checkout': () => problem(409, type, [{ field: 'oak-chair', message: 'short' }]),
      });

      expect(await placeOrder(cart, noWait)).toEqual({ kind: type, slugs: ['oak-chair'] });
    },
  );

  it('reports too many pending orders', async () => {
    stubFetch({ '/api/checkout': () => problem(409, 'too-many-pending-orders') });

    expect(await placeOrder(cart, noWait)).toEqual({ kind: 'too-many-pending-orders' });
  });

  it('treats other 409 types as failures', async () => {
    stubFetch({ '/api/checkout': () => problem(409, 'something-else') });

    expect((await placeOrder(cart, noWait)).kind).toBe('failed');
  });

  it('treats a 409 without a type as a failure', async () => {
    stubFetch({ '/api/checkout': () => jsonResponse({ status: 409 }, 409) });

    expect((await placeOrder(cart, noWait)).kind).toBe('failed');
  });

  it('drops the key when the server says it was used for another request', async () => {
    stubFetch({ '/api/checkout': () => problem(422, 'idempotency-key-reused') });

    expect((await placeOrder(cart, noWait)).kind).toBe('failed');
    expect(sessionStorage.getItem('checkout:4')).toBeNull();
  });

  it('keeps the key on a 422 of another type', async () => {
    stubFetch({ '/api/checkout': () => problem(422, 'validation') });

    await placeOrder(cart, noWait);

    expect(sessionStorage.getItem('checkout:4')).not.toBeNull();
  });

  it('shows a 413 as a plain failure', async () => {
    stubFetch({ '/api/checkout': () => problem(413, 'body-too-large') });

    const result = await placeOrder(cart, noWait);

    expect(result.kind === 'failed' && result.error.status).toBe(413);
  });
});
