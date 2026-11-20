import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { anonymousMe, cartOf, customerMe, ORDER_ID, orderOf, orderPage } from '@/test/fixtures';
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
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function stubOrders(responders: Record<string, Responder>, me: unknown = customerMe) {
  return stubFetch({
    '/api/me': () => jsonResponse(me),
    '/api/cart': () => jsonResponse(cartOf([], 0)),
    ...responders,
  });
}

describe('orders list', () => {
  it('lists orders with status, items and total, and links to each', async () => {
    const orders = [
      orderOf(),
      orderOf({ id: '22222222-2222-4222-8222-222222222222', status: 'CANCELLED' }),
    ];
    stubOrders({ '/api/orders': () => jsonResponse(orderPage(orders)) });
    const router = renderApp('/orders');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Your orders');
    expect(await screen.findByText('Awaiting payment')).toBeTruthy();
    expect(screen.getByText('Cancelled')).toBeTruthy();
    expect(document.title).toBe('Your orders | Shop');
    fireEvent.click(screen.getAllByRole('link', { name: /View order/ })[0] as HTMLElement);
    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`));
  });

  it('pages like the admin lists', async () => {
    const fetchMock = stubOrders({
      '/api/orders': () =>
        jsonResponse(orderPage([orderOf()], { page: 1, totalPages: 3, totalItems: 41 })),
    });
    renderApp('/orders?page=2');

    expect(await screen.findByRole('navigation', { name: 'Pagination' })).toBeTruthy();
    expect(urlsRequested(fetchMock, '/api/orders')[0]?.searchParams.get('page')).toBe('1');
  });

  it('says there are no orders', async () => {
    stubOrders({ '/api/orders': () => jsonResponse(orderPage([])) });
    renderApp('/orders');

    expect(await screen.findByText('You have no orders yet')).toBeTruthy();
  });

  it('offers the Orders link in the user menu', async () => {
    stubOrders({ '/api/orders': () => jsonResponse(orderPage([])) });
    renderApp('/orders');

    expect(await screen.findByRole('link', { name: 'Orders' })).toBeTruthy();
  });

  it.each([
    [401, anonymousMe, 'You need to sign in to shop.'],
    [403, customerMe, 'This area is only for customers.'],
  ])('shows the gate for a %d', async (status, me, text) => {
    stubOrders({ '/api/orders': () => problemResponse(status) }, me);
    renderApp('/orders');

    expect(await screen.findByText(text)).toBeTruthy();
  });

  it('shows an error and a loading message', async () => {
    stubOrders({ '/api/orders': () => problemResponse(500) });
    renderApp('/orders');

    expect((await screen.findByRole('alert')).textContent).toBe('Your orders could not be loaded.');
  });

  it('shows loading', async () => {
    stubOrders({ '/api/orders': () => new Promise<Response>(() => undefined) });
    renderApp('/orders');

    expect(await screen.findByText('Loading orders')).toBeTruthy();
  });
});

describe('order detail', () => {
  const path = `/api/orders/${ORDER_ID}`;

  it('shows an unpaid order with its hold and lines', async () => {
    stubOrders({ [path]: () => jsonResponse(orderOf()) });
    renderApp(`/orders/${ORDER_ID}`);

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Order');
    expect(await screen.findByText('Awaiting payment')).toBeTruthy();
    expect(screen.getByText(/^Reserved until/).textContent).toBe(
      'Reserved until Jan 1, 2999, 10:15:00 AM UTC',
    );
    expect(screen.getByText('Oak chair × 2')).toBeTruthy();
    expect(screen.getByText('Total €100.00')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Cancel order' })).toBeTruthy();
  });

  it.each([
    ['CANCELLED', 'Cancelled'],
    ['EXPIRED', 'Expired'],
  ] as const)('shows a %s order without the cancel button', async (status, label) => {
    stubOrders({ [path]: () => jsonResponse(orderOf({ status })) });
    renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByText(label)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Cancel order' })).toBeNull();
    expect(screen.queryByText(/^Reserved until/)).toBeNull();
  });

  it('treats an unpaid order past its hold as expired', async () => {
    stubOrders({ [path]: () => jsonResponse(orderOf({ expiresAt: '2020-01-01T00:00:00Z' })) });
    renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByText('Expired')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Cancel order' })).toBeNull();
  });

  it('hides the cancel button when the hold ends while the page is open', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    vi.setSystemTime(new Date('2026-11-19T10:00:00Z'));
    stubOrders({ [path]: () => jsonResponse(orderOf({ expiresAt: '2026-11-19T10:00:30Z' })) });
    renderApp(`/orders/${ORDER_ID}`);
    await vi.waitFor(() =>
      expect(screen.getByRole('button', { name: 'Cancel order' })).toBeTruthy(),
    );

    await act(() => vi.advanceTimersByTimeAsync(31_000));

    expect(screen.queryByRole('button', { name: 'Cancel order' })).toBeNull();
    expect(screen.getByText('Expired')).toBeTruthy();
  });

  it('cancels the order and confirms', async () => {
    stubOrders({
      [path]: () => jsonResponse(orderOf()),
      [`${path}/cancel`]: () => jsonResponse(orderOf({ status: 'CANCELLED' })),
      '/api/orders': () => jsonResponse(orderPage([])),
    });
    renderApp(`/orders/${ORDER_ID}`);

    fireEvent.click(await screen.findByRole('button', { name: 'Cancel order' }));

    expect((await screen.findByRole('status')).textContent).toBe('Order cancelled');
    expect(screen.getByText('Cancelled')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Cancel order' })).toBeNull();
  });

  it.each([
    [409, 'This order can no longer be cancelled.'],
    [500, 'The order could not be cancelled. Try again.'],
  ])('explains a %d when cancelling and reloads the order', async (status, message) => {
    const fetchMock = stubOrders({
      [path]: () => jsonResponse(orderOf()),
      [`${path}/cancel`]: () => problemResponse(status),
    });
    renderApp(`/orders/${ORDER_ID}`);

    fireEvent.click(await screen.findByRole('button', { name: 'Cancel order' }));

    expect((await screen.findByRole('alert')).textContent).toBe(message);
    await waitFor(() => expect(urlsRequested(fetchMock, path).length).toBeGreaterThan(1));
  });

  it.each([404, 400])('shows not found for a %d', async (status) => {
    stubOrders({ [path]: () => problemResponse(status) });
    renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByRole('heading', { name: 'Order not found' })).toBeTruthy();
  });

  it.each([
    [401, anonymousMe, 'You need to sign in to shop.'],
    [403, customerMe, 'This area is only for customers.'],
  ])('shows the gate for a %d', async (status, me, text) => {
    stubOrders({ [path]: () => problemResponse(status) }, me);
    renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByText(text)).toBeTruthy();
  });

  it('shows an error', async () => {
    stubOrders({ [path]: () => problemResponse(500) });
    renderApp(`/orders/${ORDER_ID}`);

    expect((await screen.findByRole('alert')).textContent).toBe('The order could not be loaded.');
  });

  it('shows loading', async () => {
    stubOrders({ [path]: () => new Promise<Response>(() => undefined) });
    renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByText('Loading order')).toBeTruthy();
  });
});
