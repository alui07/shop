import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { anonymousMe, cartOf, customerMe, ORDER_ID, orderOf } from '@/test/fixtures';
import {
  jsonResponse,
  problemResponse,
  stubFetch,
  urlsRequested,
  type Responder,
} from '@/test/fetchStub';
import { isReturnedFromPayment, RETURNED_FROM_PAYMENT } from '@/lib/paymentState';
import { renderApp } from '@/test/renderApp';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const orderPath = `/api/orders/${ORDER_ID}`;
const pspPath = '/api/fake-psp/payments/tok_1';

function stubShop(responders: Record<string, Responder>, me: unknown = customerMe) {
  return stubFetch({
    '/api/me': () => jsonResponse(me),
    '/api/cart': () => jsonResponse(cartOf([], 0)),
    '/api/orders': () =>
      jsonResponse({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }),
    ...responders,
  });
}

const pspPayment = {
  amountMinor: 10000,
  currency: 'EUR',
  expiresAt: '2999-01-01T10:20:00Z',
  merchantReference: '22222222-2222-4222-8222-222222222222',
  returnUrl: `/orders/${ORDER_ID}`,
  status: 'PENDING',
};

describe('order statuses and payment notes', () => {
  it.each([
    ['PAID', 'Paid'],
    ['REFUND_PENDING', 'Refund pending'],
    ['REFUNDED', 'Refunded'],
  ] as const)('shows a %s order without actions', async (status, label) => {
    stubShop({ [orderPath]: () => jsonResponse(orderOf({ status, paymentStatus: 'SUCCEEDED' })) });
    renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByText(label)).toBeTruthy();
    expect(screen.getByText('Payment: Received')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Pay now' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Cancel order' })).toBeNull();
  });

  it('notes a late payment', async () => {
    stubShop({
      [orderPath]: () =>
        jsonResponse(orderOf({ status: 'PAID', paymentStatus: 'SUCCEEDED', latePayment: true })),
    });
    renderApp(`/orders/${ORDER_ID}`);

    expect(await screen.findByText('Paid after the reservation ended')).toBeTruthy();
  });

  it('shows no payment note for an order without a payment', async () => {
    stubShop({ [orderPath]: () => jsonResponse(orderOf()) });
    renderApp(`/orders/${ORDER_ID}`);

    await screen.findByText('Awaiting payment');
    expect(screen.queryByText(/^Payment:/)).toBeNull();
    expect(screen.queryByText('Paid after the reservation ended')).toBeNull();
  });
});

describe('Pay now', () => {
  it('is hidden once the reservation has ended', async () => {
    stubShop({ [orderPath]: () => jsonResponse(orderOf({ expiresAt: '2020-01-01T00:00:00Z' })) });
    renderApp(`/orders/${ORDER_ID}`);

    await screen.findByText('Expired');
    expect(screen.queryByRole('button', { name: 'Pay now' })).toBeNull();
  });

  it('starts the payment and opens the provider page', async () => {
    const fetchMock = stubShop({
      [orderPath]: () => jsonResponse(orderOf()),
      [`${orderPath}/payment`]: () => jsonResponse({ redirectUrl: '/fake-psp/tok_1' }),
      [pspPath]: () => jsonResponse(pspPayment),
    });
    const router = renderApp(`/orders/${ORDER_ID}`);

    fireEvent.click(await screen.findByRole('button', { name: 'Pay now' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/fake-psp/tok_1'));
    expect(urlsRequested(fetchMock, `${orderPath}/payment`)).toHaveLength(1);
  });

  it('ignores a second click while the payment starts', async () => {
    let release: (response: Response) => void = () => undefined;
    const fetchMock = stubShop({
      [orderPath]: () => jsonResponse(orderOf()),
      [`${orderPath}/payment`]: () => new Promise<Response>((resolve) => (release = resolve)),
    });
    renderApp(`/orders/${ORDER_ID}`);

    const button = await screen.findByRole('button', { name: 'Pay now' });
    fireEvent.click(button);
    await waitFor(() => expect(button.getAttribute('aria-disabled')).toBe('true'));
    fireEvent.click(button);
    release(problemResponse(500));

    await screen.findByRole('alert');
    expect(urlsRequested(fetchMock, `${orderPath}/payment`)).toHaveLength(1);
  });

  it.each([
    [409, 'This order can no longer be paid.'],
    [500, 'The payment could not be started. Try again.'],
  ])('explains a %d', async (status, message) => {
    stubShop({
      [orderPath]: () => jsonResponse(orderOf()),
      [`${orderPath}/payment`]: () => problemResponse(status),
    });
    renderApp(`/orders/${ORDER_ID}`);

    fireEvent.click(await screen.findByRole('button', { name: 'Pay now' }));

    expect((await screen.findByRole('alert')).textContent).toBe(message);
  });

  it.each([
    'https://evil.example/fake-psp/tok_1',
    '//evil.example/fake-psp/tok_1',
    'javascript:alert(1)',
  ])('refuses the redirect %s', async (redirectUrl) => {
    stubShop({
      [orderPath]: () => jsonResponse(orderOf()),
      [`${orderPath}/payment`]: () => jsonResponse({ redirectUrl }),
    });
    const router = renderApp(`/orders/${ORDER_ID}`);

    fireEvent.click(await screen.findByRole('button', { name: 'Pay now' }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The payment could not be started. Try again.',
    );
    expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`);
  });
});

describe('fake provider page', () => {
  function stubPayment(complete: Responder, payment: unknown = pspPayment) {
    return stubShop({
      [pspPath]: () => jsonResponse(payment),
      [`${pspPath}/complete`]: complete,
      [orderPath]: () => jsonResponse(orderOf({ status: 'PAID', paymentStatus: 'SUCCEEDED' })),
    });
  }

  it('shows the amount, reference, expiry and the test banner', async () => {
    stubPayment(() => jsonResponse(pspPayment));
    renderApp('/fake-psp/tok_1');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe(
      'Fake payment provider',
    );
    expect(screen.getByText('Test page: no real payment is taken')).toBeTruthy();
    expect(await screen.findByText('Amount €100.00')).toBeTruthy();
    expect(screen.getByText(`Merchant reference ${pspPayment.merchantReference}`)).toBeTruthy();
    expect(screen.getByText('Jan 1, 2999, 10:20:00 AM UTC')).toBeTruthy();
    expect(document.title).toBe('Fake payment provider | Shop');
  });

  it.each([
    ['Pay', 'SUCCEEDED'],
    ['Decline', 'DECLINED'],
  ])('%s completes with %s and returns to the order', async (name, outcome) => {
    const bodies: unknown[] = [];
    stubPayment(async (_url, request) => {
      bodies.push(await request.json());
      return jsonResponse({ ...pspPayment, status: outcome });
    });
    const router = renderApp('/fake-psp/tok_1');

    fireEvent.click(await screen.findByRole('button', { name }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`));
    expect(bodies).toEqual([{ outcome }]);
    expect(isReturnedFromPayment(router.state.location.state)).toBe(outcome === 'SUCCEEDED');
  });

  it('shows the normal order view after a declined payment, without the confirming message', async () => {
    stubShop({
      [pspPath]: () => jsonResponse(pspPayment),
      [`${pspPath}/complete`]: () => jsonResponse({ ...pspPayment, status: 'DECLINED' }),
      [orderPath]: () => jsonResponse(orderOf({ paymentStatus: 'FAILED' })),
    });
    renderApp('/fake-psp/tok_1');

    fireEvent.click(await screen.findByRole('button', { name: 'Decline' }));

    expect(await screen.findByText('Awaiting payment')).toBeTruthy();
    expect(screen.queryByText('Payment is being confirmed.')).toBeNull();
    expect(screen.getByRole('button', { name: 'Pay now' })).toBeTruthy();
  });

  it('ignores a second click while completing', async () => {
    let release: (response: Response) => void = () => undefined;
    const fetchMock = stubPayment(() => new Promise<Response>((resolve) => (release = resolve)));
    renderApp('/fake-psp/tok_1');

    const pay = await screen.findByRole('button', { name: 'Pay' });
    fireEvent.click(pay);
    await waitFor(() => expect(pay.getAttribute('aria-disabled')).toBe('true'));
    fireEvent.click(pay);
    release(problemResponse(500));

    await screen.findByRole('alert');
    expect(urlsRequested(fetchMock, `${pspPath}/complete`)).toHaveLength(1);
  });

  it('says the payment has expired on a payment-state conflict and focuses the message', async () => {
    stubPayment(
      () =>
        new Response(
          JSON.stringify({ type: 'tag:shop.dev,2026:payment-state', status: 409, title: 'x' }),
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
    );
    renderApp('/fake-psp/tok_1');

    fireEvent.click(await screen.findByRole('button', { name: 'Pay' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('This payment has expired');
    await waitFor(() => expect(document.activeElement).toBe(alert));
    expect(screen.getByRole('link', { name: 'Back to order' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Pay' })).toBeNull();
  });

  const conflict = () =>
    new Response(
      JSON.stringify({ type: 'tag:shop.dev,2026:payment-state', status: 409, title: 'x' }),
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    );

  function stubConflict(secondRead: Responder) {
    let reads = 0;
    stubShop({
      [pspPath]: (url, request) =>
        ++reads === 1 ? jsonResponse(pspPayment) : secondRead(url, request),
      [`${pspPath}/complete`]: conflict,
      [orderPath]: () => jsonResponse(orderOf({ status: 'PAID', paymentStatus: 'SUCCEEDED' })),
    });
  }

  it('says the payment is already completed when the refreshed payment is no longer pending', async () => {
    stubConflict(() => jsonResponse({ ...pspPayment, status: 'SUCCEEDED' }));
    const router = renderApp('/fake-psp/tok_1');

    fireEvent.click(await screen.findByRole('button', { name: 'Pay' }));

    const message = await screen.findByText('This payment is already completed.');
    expect(screen.queryByText('This payment has expired')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Pay' })).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(message));
    fireEvent.click(screen.getByRole('link', { name: 'Back to order' }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`));
  });

  it('still says expired when the refreshed payment cannot be read', async () => {
    stubConflict(() => problemResponse(500));
    renderApp('/fake-psp/tok_1');

    fireEvent.click(await screen.findByRole('button', { name: 'Pay' }));

    expect(await screen.findByText('This payment has expired')).toBeTruthy();
  });

  it('shows the expired panel instead of the buttons when the session has already ended', async () => {
    stubPayment(() => jsonResponse(pspPayment), {
      ...pspPayment,
      expiresAt: '2020-01-01T00:00:00Z',
    });
    renderApp('/fake-psp/tok_1');

    expect((await screen.findByRole('alert')).textContent).toBe('This payment has expired');
    expect(screen.queryByRole('button', { name: 'Pay' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Decline' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Back to order' })).toBeTruthy();
  });

  it('replaces the buttons when the session ends while the page is open', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    vi.setSystemTime(new Date('2026-11-19T10:00:00Z'));
    stubPayment(() => jsonResponse(pspPayment), {
      ...pspPayment,
      expiresAt: '2026-11-19T10:00:30Z',
    });
    renderApp('/fake-psp/tok_1');
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Pay' })).toBeTruthy());

    await act(() => vi.advanceTimersByTimeAsync(31_000));

    expect(screen.queryByRole('button', { name: 'Pay' })).toBeNull();
    expect(screen.getByText('This payment has expired')).toBeTruthy();
  });

  it('shows a generic error for any other failure', async () => {
    stubPayment(() => problemResponse(500));
    renderApp('/fake-psp/tok_1');

    fireEvent.click(await screen.findByRole('button', { name: 'Decline' }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The payment could not be completed. Try again.',
    );
  });

  it('does not follow a return address that is not an order of this site', async () => {
    stubPayment(() => jsonResponse({ ...pspPayment, returnUrl: 'https://evil.example/orders/x' }));
    const router = renderApp('/fake-psp/tok_1');

    fireEvent.click(await screen.findByRole('button', { name: 'Pay' }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The payment could not be completed. Try again.',
    );
    expect(router.state.location.pathname).toBe('/fake-psp/tok_1');
  });

  it('offers no buttons for a payment that is already completed', async () => {
    stubPayment(() => jsonResponse(pspPayment), { ...pspPayment, status: 'SUCCEEDED' });
    renderApp('/fake-psp/tok_1');

    expect(await screen.findByText('This payment is already completed.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Pay' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Back to order' }).getAttribute('href')).toBe(
      `/orders/${ORDER_ID}`,
    );
  });

  it('offers no way back when the return address is not an order of this site', async () => {
    stubPayment(() => jsonResponse(pspPayment), {
      ...pspPayment,
      status: 'DECLINED',
      returnUrl: 'https://evil.example/orders/x',
    });
    renderApp('/fake-psp/tok_1');

    await screen.findByText('This payment is already completed.');
    expect(screen.queryByRole('link', { name: 'Back to order' })).toBeNull();
  });

  it('shows not found for an unknown payment', async () => {
    stubShop({ [pspPath]: () => problemResponse(404) });
    renderApp('/fake-psp/tok_1');

    expect(await screen.findByRole('heading', { name: 'Payment not found' })).toBeTruthy();
  });

  it.each([
    [401, anonymousMe, 'You need to sign in to shop.'],
    [403, customerMe, 'This area is only for customers.'],
  ])('shows the gate for a %d', async (status, me, text) => {
    stubShop({ [pspPath]: () => problemResponse(status) }, me);
    renderApp('/fake-psp/tok_1');

    expect(await screen.findByText(text)).toBeTruthy();
  });

  it('shows an error and a loading message', async () => {
    stubShop({ [pspPath]: () => problemResponse(500) });
    renderApp('/fake-psp/tok_1');

    expect((await screen.findByRole('alert')).textContent).toBe('The payment could not be loaded.');
  });

  it('shows loading', async () => {
    stubShop({ [pspPath]: () => new Promise<Response>(() => undefined) });
    renderApp('/fake-psp/tok_1');

    expect(await screen.findByText('Loading payment')).toBeTruthy();
  });
});

describe('waiting for the payment confirmation', () => {
  function renderReturning(responders: Record<string, Responder>) {
    const fetchMock = stubShop(responders);
    const router = renderApp('/');
    return { fetchMock, router };
  }

  async function arrive(router: ReturnType<typeof renderApp>) {
    await router.navigate({
      to: '/orders/$id',
      params: { id: ORDER_ID },
      state: RETURNED_FROM_PAYMENT,
    });
  }

  it.each(['PENDING_PAYMENT', 'EXPIRED'] as const)(
    'polls a %s order and shows the paid order with its late note',
    async (status) => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
      let calls = 0;
      const { router } = renderReturning({
        [orderPath]: () =>
          jsonResponse(
            ++calls < 3
              ? orderOf({ status })
              : orderOf({ status: 'PAID', paymentStatus: 'SUCCEEDED', latePayment: true }),
          ),
      });
      await act(() => arrive(router));
      await vi.waitFor(() => expect(screen.getByText('Payment is being confirmed.')).toBeTruthy());
      expect(screen.queryByRole('button', { name: 'Pay now' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Cancel order' })).toBeNull();

      await act(() => vi.advanceTimersByTimeAsync(2500));

      await vi.waitFor(() => expect(screen.getByText('Paid')).toBeTruthy());
      expect(screen.getByText('Paid after the reservation ended')).toBeTruthy();
      expect(screen.queryByText('Payment is being confirmed.')).toBeNull();
      expect(calls).toBe(3);
    },
  );

  it('does not poll or show the confirming message once the server reports the payment failed', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const { fetchMock, router } = renderReturning({
      [orderPath]: () => jsonResponse(orderOf({ paymentStatus: 'FAILED' })),
    });
    await act(() => arrive(router));
    await vi.waitFor(() => expect(screen.getByText('Awaiting payment')).toBeTruthy());

    await act(() => vi.advanceTimersByTimeAsync(5000));

    expect(screen.queryByText('Payment is being confirmed.')).toBeNull();
    expect(screen.getByText('Payment: Failed')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Pay now' })).toBeTruthy();
    expect(urlsRequested(fetchMock, orderPath)).toHaveLength(1);
  });

  it('does not poll an expired order without the router state', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const fetchMock = stubShop({ [orderPath]: () => jsonResponse(orderOf({ status: 'EXPIRED' })) });
    renderApp(`/orders/${ORDER_ID}`);
    await vi.waitFor(() => expect(screen.getByText('Expired')).toBeTruthy());

    await act(() => vi.advanceTimersByTimeAsync(5000));

    expect(screen.queryByText('Payment is being confirmed.')).toBeNull();
    expect(urlsRequested(fetchMock, orderPath)).toHaveLength(1);
  });

  it('falls back to the plain presentation after 20 seconds', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const { fetchMock, router } = renderReturning({
      [orderPath]: () => jsonResponse(orderOf({ status: 'EXPIRED' })),
    });
    await act(() => arrive(router));
    await vi.waitFor(() => expect(screen.getByText('Payment is being confirmed.')).toBeTruthy());

    await act(() => vi.advanceTimersByTimeAsync(20_500));

    expect(screen.queryByText('Payment is being confirmed.')).toBeNull();
    expect(screen.getByText('Expired')).toBeTruthy();
    const callsAtTimeout = urlsRequested(fetchMock, orderPath).length;
    expect(callsAtTimeout).toBeGreaterThan(10);
    await act(() => vi.advanceTimersByTimeAsync(5000));
    expect(urlsRequested(fetchMock, orderPath)).toHaveLength(callsAtTimeout);
  });

  it('stops polling once the order is paid', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const { fetchMock, router } = renderReturning({
      [orderPath]: () => jsonResponse(orderOf({ status: 'PAID', paymentStatus: 'SUCCEEDED' })),
    });
    await act(() => arrive(router));
    await vi.waitFor(() => expect(screen.getByText('Paid')).toBeTruthy());

    await act(() => vi.advanceTimersByTimeAsync(5000));

    expect(urlsRequested(fetchMock, orderPath)).toHaveLength(1);
    expect(screen.queryByText('Payment is being confirmed.')).toBeNull();
  });
});
