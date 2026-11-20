import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { anonymousMe, cartLine, cartOf, customerMe, ORDER_ID, orderOf } from '@/test/fixtures';
import {
  jsonResponse,
  problemResponse,
  stubFetch,
  urlsRequested,
  type Responder,
} from '@/test/fetchStub';
import { renderApp } from '@/test/renderApp';

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

const cart = cartOf([cartLine('oak-chair', 'Oak chair', 2), cartLine('pine-chair', 'Pine chair')]);

function problem(status: number, name: string, errors: { field: string; message: string }[] = []) {
  return jsonResponse({ type: `tag:shop.dev,2026:${name}`, status, errors }, status);
}

function stubCheckout(checkout: Responder, cartResponder: Responder = () => jsonResponse(cart)) {
  return stubFetch({
    '/api/me': () => jsonResponse(customerMe),
    '/api/cart': cartResponder,
    '/api/checkout': checkout,
    [`/api/orders/${ORDER_ID}`]: () => jsonResponse(orderOf()),
    '/api/orders': () =>
      jsonResponse({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }),
  });
}

async function placeOrderClick() {
  fireEvent.click(await screen.findByRole('button', { name: 'Place order' }));
}

describe('checkout view', () => {
  it('summarises the cart', async () => {
    stubCheckout(() => jsonResponse(orderOf(), 201));
    renderApp('/checkout');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Checkout');
    expect(await screen.findByText('Oak chair × 2')).toBeTruthy();
    expect(screen.getByText('Total €150.00')).toBeTruthy();
    expect(document.title).toBe('Checkout | Shop');
  });

  it('places the order, drops the key and opens the order', async () => {
    const fetchMock = stubCheckout(() => jsonResponse(orderOf(), 201));
    const router = renderApp('/checkout');

    await placeOrderClick();

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`));
    const request = fetchMock.mock.calls
      .map(([call]) => call)
      .find((call) => call.url.endsWith('/api/checkout'));
    expect(request?.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(await request?.clone().json()).toEqual({ cartVersion: 3, expectedTotalMinor: 15000 });
    expect(sessionStorage.getItem('checkout:3')).toBeNull();
    expect(await screen.findByText('Awaiting payment')).toBeTruthy();
  });

  it.each(['cart-changed', 'price-changed'])(
    'says the cart changed on %s and reloads it',
    async (type) => {
      const fetchMock = stubCheckout(() => problem(409, type));
      renderApp('/checkout');

      await placeOrderClick();

      const alert = await screen.findByRole('alert');
      expect(alert.textContent).toBe('Your cart changed. Check it and try again.');
      expect(document.activeElement).toBe(alert);
      await waitFor(() => expect(urlsRequested(fetchMock, '/api/cart').length).toBeGreaterThan(1));
    },
  );

  it('lists the products that are out of stock', async () => {
    stubCheckout(() => problem(409, 'out-of-stock', [{ field: 'pine-chair', message: 'short' }]));
    renderApp('/checkout');

    await placeOrderClick();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Not enough stock');
    expect(within(alert).getByText('Pine chair')).toBeTruthy();
  });

  it('shows the reservation limit with the products it hit, falling back to the slug', async () => {
    stubCheckout(() =>
      problem(409, 'reservation-limit', [
        { field: 'oak-chair', message: 'limit' },
        { field: 'gone-product', message: 'limit' },
      ]),
    );
    renderApp('/checkout');

    await placeOrderClick();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(
      "You can't reserve more of this product until your unpaid orders are paid or cancelled.",
    );
    expect(within(alert).getByText('Oak chair')).toBeTruthy();
    expect(within(alert).getByText('gone-product')).toBeTruthy();
  });

  it('says there are too many orders awaiting payment', async () => {
    stubCheckout(() => problem(409, 'too-many-pending-orders'));
    renderApp('/checkout');

    await placeOrderClick();

    expect((await screen.findByRole('alert')).textContent).toBe(
      'You have too many orders awaiting payment.',
    );
  });

  it.each([
    [413, 'The order could not be placed. Try again.'],
    [503, 'The shop is temporarily unavailable. Try again in a moment.'],
  ])('shows a %d as a plain error and keeps the key', async (status, message) => {
    stubCheckout(() => problem(status, 'body-too-large'));
    renderApp('/checkout');

    await placeOrderClick();

    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(sessionStorage.getItem('checkout:3')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Place order' }).hasAttribute('disabled')).toBe(
      false,
    );
  });

  it('clears the previous alert when trying again', async () => {
    let calls = 0;
    stubCheckout(() => (++calls === 1 ? problem(500, 'internal') : jsonResponse(orderOf(), 201)));
    const router = renderApp('/checkout');

    await placeOrderClick();
    await screen.findByRole('alert');
    await placeOrderClick();

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`));
  });

  it('keeps focus where the user put it when the cart reloads after an alert', async () => {
    let cartCalls = 0;
    let reloadCart: () => void = () => {};
    const reloaded = new Promise<void>((resolve) => (reloadCart = resolve));
    stubCheckout(
      () => problem(409, 'cart-changed'),
      async () => {
        if (++cartCalls > 1) await reloaded;
        return jsonResponse(cart);
      },
    );
    renderApp('/checkout');

    await placeOrderClick();
    const alert = await screen.findByRole('alert');
    await waitFor(() => expect(cartCalls).toBe(2));
    const placeOrder = screen.getByRole('button', { name: 'Place order' });
    placeOrder.focus();
    reloadCart();
    await waitFor(() => expect(document.activeElement).toBe(placeOrder));
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(document.activeElement).toBe(placeOrder);
    expect(alert.isConnected).toBe(true);
  });

  it('says the cart is empty without offering to place an order', async () => {
    stubCheckout(
      () => jsonResponse(orderOf(), 201),
      () => jsonResponse(cartOf([], 0)),
    );
    renderApp('/checkout');

    expect(await screen.findByText('Your cart is empty')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Place order' })).toBeNull();
  });

  it('blocks placing an order while a line is unavailable', async () => {
    stubCheckout(
      () => jsonResponse(orderOf(), 201),
      () => jsonResponse(cartOf([cartLine('oak-chair', 'Oak chair', 9, false)])),
    );
    renderApp('/checkout');

    expect(await screen.findByText(/not available in this quantity/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Place order' }).hasAttribute('disabled')).toBe(true);
  });

  it('asks anonymous visitors to sign in', async () => {
    stubFetch({
      '/api/me': () => jsonResponse(anonymousMe),
      '/api/cart': () => problemResponse(401),
    });
    renderApp('/checkout');

    expect(await screen.findByText('You need to sign in to shop.')).toBeTruthy();
  });

  it('shows an alert and a loading message for the cart', async () => {
    stubCheckout(
      () => jsonResponse(orderOf(), 201),
      () => problemResponse(500),
    );
    renderApp('/checkout');

    expect((await screen.findByRole('alert')).textContent).toBe('The cart could not be loaded.');
  });

  it('shows a loading message', async () => {
    stubCheckout(
      () => jsonResponse(orderOf(), 201),
      () => new Promise<Response>(() => undefined),
    );
    renderApp('/checkout');

    expect(await screen.findByText('Loading cart')).toBeTruthy();
  });
});
