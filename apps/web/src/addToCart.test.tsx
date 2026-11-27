import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ADD_RETRY_DELAY_MS, MOST_ALLOWED_TEXT } from '@/components/addToCartRules';
import { cartQuery } from '@/api/cart';
import {
  anonymousMe,
  cartLine,
  cartOf,
  categories,
  customerMe,
  oakChair,
  oakChairDetail,
  productPage,
} from '@/test/fixtures';
import {
  jsonResponse,
  problemResponse,
  stubFetch,
  urlsRequested,
  type Responder,
} from '@/test/fetchStub';
import { renderApp } from '@/test/renderApp';

const ADDITIONS = '/api/cart/lines/oak-chair/additions';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const UNCONFIRMED = 'We could not confirm the addition. Check your cart before trying again.';
const IN_PROGRESS = 'tag:shop.dev,2026:idempotency-in-progress';
const LINE_LIMIT = 'tag:shop.dev,2026:line-quantity-limit';
const CART_FULL = 'tag:shop.dev,2026:cart-full';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const product: Record<string, Responder> = {
  '/api/products/oak-chair': () => jsonResponse(oakChairDetail),
};

function cartWith(quantity: number, version = 3) {
  return cartOf(quantity > 0 ? [cartLine('oak-chair', 'Oak chair', quantity)] : [], version);
}

function problemBody(status: number, type: string, extra: Record<string, unknown> = {}) {
  return jsonResponse({ type, status, errors: [], ...extra }, status);
}

function stubShop(cart: Responder, additions: Responder, me: unknown = customerMe) {
  return stubFetch({
    '/api/me': () => jsonResponse(me),
    '/api/cart': cart,
    '/api/categories': () => jsonResponse(categories),
    '/api/products': () => jsonResponse(productPage([oakChair])),
    ...product,
    [ADDITIONS]: additions,
  });
}

function clickAdd() {
  fireEvent.click(screen.getByRole('button', { name: 'Add to cart' }));
}

async function openProduct(cartQuantity = 0) {
  renderApp('/products/oak-chair');
  await screen.findByRole('heading', { level: 1, name: 'Oak chair' });
  if (cartQuantity < 10) await screen.findByRole('button', { name: 'Add to cart' });
}

async function advanceRetry() {
  await act(() => vi.advanceTimersByTimeAsync(ADD_RETRY_DELAY_MS));
}

function additionKeys(fetchMock: ReturnType<typeof stubFetch>): string[] {
  return fetchMock.mock.calls
    .map(([request]) => request)
    .filter((request) => new URL(request.url).pathname === ADDITIONS)
    .map((request) => request.headers.get('Idempotency-Key') ?? '');
}

describe('add to cart', () => {
  it('offers sign in to anonymous visitors', async () => {
    stubShop(
      () => problemResponse(401),
      () => problemResponse(401),
      anonymousMe,
    );
    renderApp('/products/oak-chair');

    expect((await screen.findByRole('link', { name: 'Sign in to buy' })).getAttribute('href')).toBe(
      '/oauth2/authorization/keycloak',
    );
    expect(screen.queryByRole('button', { name: 'Add to cart' })).toBeNull();
  });

  it('posts the chosen quantity with an idempotency key and confirms', async () => {
    const bodies: unknown[] = [];
    const methods: string[] = [];
    const fetchMock = stubShop(
      () => jsonResponse(cartWith(0, 0)),
      async (_url, request) => {
        methods.push(request.method);
        bodies.push(await request.json());
        return jsonResponse(cartWith(3));
      },
    );
    renderApp('/products/oak-chair');

    fireEvent.change(await screen.findByLabelText('Quantity'), { target: { value: '3' } });
    clickAdd();

    expect(await screen.findByText('Added to cart')).toBeTruthy();
    expect(bodies).toEqual([{ quantity: 3 }]);
    expect(methods).toEqual(['POST']);
    expect(additionKeys(fetchMock)[0]).toMatch(UUID);
    expect(await screen.findByRole('link', { name: 'Cart (3)' })).toBeTruthy();
    expect(screen.getByText('In your cart: 3')).toBeTruthy();
  });

  it('sends a different key for every click', async () => {
    const fetchMock = stubShop(
      () => jsonResponse(cartWith(0, 0)),
      () => jsonResponse(cartWith(1)),
    );
    await openProduct();

    clickAdd();
    await waitFor(() => expect(additionKeys(fetchMock)).toHaveLength(1));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Add to cart' }).hasAttribute('disabled')).toBe(
        false,
      ),
    );
    clickAdd();
    await waitFor(() => expect(additionKeys(fetchMock)).toHaveLength(2));

    const keys = additionKeys(fetchMock);
    expect(new Set(keys).size).toBe(2);
  });

  describe('retries with the same key', () => {
    it.each([
      ['a network failure', () => Promise.reject(new TypeError('offline'))],
      ['a 500', () => problemBody(500, 'tag:shop.dev,2026:internal')],
      ['an idempotency-in-progress 409', () => problemBody(409, IN_PROGRESS)],
    ])('after %s, then succeeds', async (_name, failure) => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      let calls = 0;
      const fetchMock = stubShop(
        () => jsonResponse(cartWith(0, 0)),
        () => (++calls < 3 ? failure() : jsonResponse(cartWith(1))),
      );
      await openProduct();

      clickAdd();
      await waitFor(() => expect(calls).toBe(1));
      await advanceRetry();
      await waitFor(() => expect(calls).toBe(2));
      await advanceRetry();

      expect(await screen.findByText('Added to cart')).toBeTruthy();
      expect(new Set(additionKeys(fetchMock)).size).toBe(1);
      expect(additionKeys(fetchMock)).toHaveLength(3);
      expect(screen.queryByRole('alert')).toBeNull();
    });

    it('keeps the button disabled while retrying, then asks to check the cart and refetches it', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const fetchMock = stubShop(
        () => jsonResponse(cartWith(0, 0)),
        () => Promise.reject(new TypeError('offline')),
      );
      await openProduct();

      clickAdd();
      await waitFor(() => expect(additionKeys(fetchMock)).toHaveLength(1));
      expect(screen.getByRole('button', { name: 'Add to cart' }).hasAttribute('disabled')).toBe(
        true,
      );
      await advanceRetry();
      await waitFor(() => expect(additionKeys(fetchMock)).toHaveLength(2));
      await advanceRetry();

      expect((await screen.findByRole('alert')).textContent).toBe(UNCONFIRMED);
      expect(additionKeys(fetchMock)).toHaveLength(3);
      await waitFor(() => expect(urlsRequested(fetchMock, '/api/cart')).toHaveLength(2));
    });

    it('says the same after three 500 answers', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const fetchMock = stubShop(
        () => jsonResponse(cartWith(0, 0)),
        () => problemBody(500, 'tag:shop.dev,2026:internal'),
      );
      await openProduct();

      clickAdd();
      await waitFor(() => expect(additionKeys(fetchMock)).toHaveLength(1));
      await advanceRetry();
      await waitFor(() => expect(additionKeys(fetchMock)).toHaveLength(2));
      await advanceRetry();

      expect((await screen.findByRole('alert')).textContent).toBe(UNCONFIRMED);
    });
  });

  describe('answers that are not retried', () => {
    it.each([
      [404, 'tag:shop.dev,2026:not-found', {}, 'This product is no longer available.'],
      [409, CART_FULL, {}, 'Your cart is full. Remove an item first.'],
      [
        409,
        LINE_LIMIT,
        { quantityInCart: 7, maxQuantity: 10 },
        'You have 7 of this product in your cart. You can add up to 3 more.',
      ],
      [
        422,
        'tag:shop.dev,2026:idempotency-key-reused',
        {},
        'The product could not be added. Try again.',
      ],
      [429, 'tag:shop.dev,2026:rate-limited', {}, 'Too many attempts. Try again in a moment.'],
    ])(
      'explains a %d answer (%s) with one request and refetches the cart',
      async (status, type, extra, message) => {
        const fetchMock = stubShop(
          () => jsonResponse(cartWith(0, 0)),
          () => problemBody(status, type, extra),
        );
        await openProduct();

        clickAdd();

        expect((await screen.findByRole('alert')).textContent).toBe(message);
        expect(additionKeys(fetchMock)).toHaveLength(1);
        await waitFor(() => expect(urlsRequested(fetchMock, '/api/cart')).toHaveLength(2));
      },
    );

    it('says the most allowed when the limit answer has no quantity', async () => {
      stubShop(
        () => jsonResponse(cartWith(0, 0)),
        () => problemBody(409, LINE_LIMIT),
      );
      await openProduct();

      clickAdd();

      expect((await screen.findByRole('alert')).textContent).toBe(MOST_ALLOWED_TEXT);
    });

    it('explains a limit answer of 10 for a stale cart with the sentence once and no alert', async () => {
      let cartCalls = 0;
      stubShop(
        () => jsonResponse(cartWith(++cartCalls === 1 ? 5 : 10)),
        () => problemBody(409, LINE_LIMIT, { quantityInCart: 10, maxQuantity: 10 }),
      );
      await openProduct(5);

      clickAdd();

      await waitFor(() => expect(screen.queryByRole('button', { name: 'Add to cart' })).toBeNull());
      expect(screen.getAllByText(MOST_ALLOWED_TEXT)).toHaveLength(1);
      expect(screen.queryByRole('alert')).toBeNull();
    });
  });

  describe('quantity already in the cart', () => {
    it('offers only the quantities that still fit', async () => {
      stubShop(
        () => jsonResponse(cartWith(7)),
        () => jsonResponse(cartWith(8)),
      );
      await openProduct(7);

      expect(await screen.findByText('In your cart: 7')).toBeTruthy();
      const options = screen.getAllByRole('option').map((option) => option.textContent);
      expect(options).toEqual(['1', '2', '3']);
    });

    it('shows no count for a product that is not in the cart', async () => {
      stubShop(
        () => jsonResponse(cartOf([cartLine('pine-chair', 'Pine chair', 4)])),
        () => jsonResponse(cartWith(1)),
      );
      await openProduct();

      expect(await screen.findByRole('link', { name: 'Cart (4)' })).toBeTruthy();
      expect(screen.queryByText(/In your cart/)).toBeNull();
      expect(screen.getAllByRole('option')).toHaveLength(10);
    });

    it('replaces the controls with the most allowed text at 10', async () => {
      stubShop(
        () => jsonResponse(cartWith(10)),
        () => jsonResponse(cartWith(10)),
      );
      await openProduct(10);

      const status = await screen.findByRole('status');
      expect(status.textContent).toBe(MOST_ALLOWED_TEXT);
      expect(screen.getAllByText(MOST_ALLOWED_TEXT)).toHaveLength(1);
      expect(screen.queryByRole('combobox')).toBeNull();
      expect(screen.queryByRole('button', { name: 'Add to cart' })).toBeNull();
      expect(document.activeElement).not.toBe(status);
    });

    it('moves focus to the text when the add fills the line and keeps the confirmation', async () => {
      stubShop(
        () => jsonResponse(cartWith(7)),
        () => jsonResponse(cartWith(10, 4)),
      );
      await openProduct(7);

      fireEvent.change(await screen.findByLabelText('Quantity'), { target: { value: '3' } });
      clickAdd();

      const text = await screen.findByText(MOST_ALLOWED_TEXT);
      expect(screen.getByText('Added to cart')).toBeTruthy();
      await waitFor(() => expect(document.activeElement).toBe(text));
      expect(text.getAttribute('role')).toBe('status');
    });

    it('does not move focus when a refetch fills the line after an earlier successful add', async () => {
      let cartCalls = 0;
      stubShop(
        () => jsonResponse(cartWith(++cartCalls === 1 ? 4 : 10)),
        () => jsonResponse(cartWith(7, 4)),
      );
      const router = renderApp('/products/oak-chair');
      await screen.findByRole('button', { name: 'Add to cart' });

      fireEvent.change(await screen.findByLabelText('Quantity'), { target: { value: '3' } });
      clickAdd();
      expect(await screen.findByText('In your cart: 7')).toBeTruthy();

      await act(() => router.options.context.queryClient.invalidateQueries());

      const text = await screen.findByText(MOST_ALLOWED_TEXT);
      expect(document.activeElement).not.toBe(text);
    });

    it('keeps a chosen quantity that still fits when the cart grows', async () => {
      stubShop(
        () => jsonResponse(cartWith(2)),
        () => jsonResponse(cartWith(6, 4)),
      );
      await openProduct(2);

      fireEvent.change(await screen.findByLabelText('Quantity'), { target: { value: '4' } });
      clickAdd();

      expect(await screen.findByText('In your cart: 6')).toBeTruthy();
      expect((screen.getByLabelText('Quantity') as HTMLSelectElement).value).toBe('4');
    });

    it('lowers the chosen quantity to what still fits', async () => {
      stubShop(
        () => jsonResponse(cartWith(0, 0)),
        () => jsonResponse(cartWith(8, 4)),
      );
      await openProduct();

      fireEvent.change(await screen.findByLabelText('Quantity'), { target: { value: '5' } });
      clickAdd();

      expect(await screen.findByText('In your cart: 8')).toBeTruthy();
      expect((screen.getByLabelText('Quantity') as HTMLSelectElement).value).toBe('2');
    });

    it('offers 1 to 10 while the cart cannot be read', async () => {
      const fetchMock = stubShop(
        () => problemResponse(500),
        () => jsonResponse(cartWith(1)),
      );
      const router = renderApp('/products/oak-chair');
      await screen.findByRole('button', { name: 'Add to cart' });

      await waitFor(() => expect(urlsRequested(fetchMock, '/api/cart').length).toBeGreaterThan(0));
      const { queryClient } = router.options.context;
      await waitFor(() =>
        expect(queryClient.getQueryState(cartQuery.queryKey)?.status).toBe('error'),
      );
      expect(screen.getAllByRole('option')).toHaveLength(10);
      expect(screen.queryByText(/In your cart/)).toBeNull();
    });
  });
});
