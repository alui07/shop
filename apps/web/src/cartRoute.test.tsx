import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const twoLines = cartOf([
  cartLine('oak-chair', 'Oak chair', 2),
  cartLine('pine-chair', 'Pine chair'),
]);

function stubShop(me: unknown, cart: Responder, extra: Record<string, Responder> = {}) {
  return stubFetch({
    '/api/me': () => jsonResponse(me),
    '/api/cart': cart,
    '/api/categories': () => jsonResponse(categories),
    '/api/products': () => jsonResponse(productPage([oakChair])),
    ...extra,
  });
}

describe('cart view', () => {
  it('lists lines with quantity, line total and the cart total', async () => {
    stubShop(customerMe, () => jsonResponse(twoLines));
    renderApp('/cart');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Cart');
    expect(await screen.findByText('Oak chair')).toBeTruthy();
    expect((screen.getByLabelText('Quantity of Oak chair') as HTMLSelectElement).value).toBe('2');
    expect(screen.getByText('€100.00')).toBeTruthy();
    expect(screen.getByText('Total €150.00')).toBeTruthy();
    expect(document.title).toBe('Cart | Shop');
    expect(screen.getByRole('button', { name: 'Checkout' }).hasAttribute('disabled')).toBe(false);
  });

  it('shows the item count in the header cart link', async () => {
    stubShop(customerMe, () => jsonResponse(twoLines));
    renderApp('/cart');

    expect(await screen.findByRole('link', { name: 'Cart (3)' })).toBeTruthy();
  });

  it('says the cart is empty and disables checkout', async () => {
    stubShop(customerMe, () => jsonResponse(cartOf([], 0)));
    renderApp('/cart');

    expect(await screen.findByText('Your cart is empty')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Checkout' }).hasAttribute('disabled')).toBe(true);
  });

  it('marks unavailable lines and disables checkout', async () => {
    stubShop(customerMe, () =>
      jsonResponse(cartOf([cartLine('oak-chair', 'Oak chair', 9, false)])),
    );
    renderApp('/cart');

    expect(await screen.findByText('Not available in this quantity')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Checkout' }).hasAttribute('disabled')).toBe(true);
  });

  it('goes to checkout', async () => {
    stubShop(customerMe, () => jsonResponse(twoLines));
    const router = renderApp('/cart');

    fireEvent.click(await screen.findByRole('button', { name: 'Checkout' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/checkout'));
  });

  it('changes a quantity and shows the returned cart', async () => {
    const put = vi.fn(() => jsonResponse(cartOf([cartLine('oak-chair', 'Oak chair', 4)])));
    stubShop(customerMe, () => jsonResponse(twoLines), { '/api/cart/lines/oak-chair': put });
    renderApp('/cart');

    fireEvent.change(await screen.findByLabelText('Quantity of Oak chair'), {
      target: { value: '4' },
    });

    await waitFor(() =>
      expect((screen.getByLabelText('Quantity of Oak chair') as HTMLSelectElement).value).toBe('4'),
    );
    expect(screen.queryByText('Pine chair')).toBeNull();
  });

  it('keeps the line controls focusable but inert while a change is pending', async () => {
    let finish: () => void = () => {};
    const gate = new Promise<void>((resolve) => (finish = resolve));
    const put = vi.fn(async () => {
      await gate;
      return jsonResponse(cartOf([cartLine('oak-chair', 'Oak chair', 4)]));
    });
    stubShop(customerMe, () => jsonResponse(twoLines), { '/api/cart/lines/oak-chair': put });
    renderApp('/cart');

    const select = (await screen.findByLabelText('Quantity of Oak chair')) as HTMLSelectElement;
    select.focus();
    fireEvent.change(select, { target: { value: '4' } });

    await waitFor(() => expect(select.getAttribute('aria-disabled')).toBe('true'));
    expect(screen.getByLabelText('Quantity of Pine chair').getAttribute('aria-disabled')).toBe(
      'true',
    );
    const removeButtons = screen.getAllByRole('button', { name: 'Remove' });
    for (const button of removeButtons) expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(document.activeElement).toBe(select);

    fireEvent.change(select, { target: { value: '5' } });
    fireEvent.click(removeButtons[0] as HTMLElement);
    expect(put).toHaveBeenCalledTimes(1);

    finish();

    await waitFor(() => expect(select.getAttribute('aria-disabled')).toBeNull());
    expect(document.activeElement).toBe(select);
  });

  it('moves focus to the heading after a line is removed', async () => {
    stubShop(customerMe, () => jsonResponse(twoLines), {
      '/api/cart/lines/pine-chair': () =>
        jsonResponse(cartOf([cartLine('oak-chair', 'Oak chair', 2)])),
    });
    renderApp('/cart');

    const row = (await screen.findByText('Pine chair')).closest('li') as HTMLElement;
    const remove = within(row).getByRole('button', { name: 'Remove' });
    remove.focus();
    fireEvent.click(remove);

    await waitFor(() => expect(screen.queryByText('Pine chair')).toBeNull());
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1 })),
    );
  });

  it('removes a line', async () => {
    stubShop(customerMe, () => jsonResponse(twoLines), {
      '/api/cart/lines/pine-chair': () =>
        jsonResponse(cartOf([cartLine('oak-chair', 'Oak chair', 2)])),
    });
    renderApp('/cart');

    const row = (await screen.findByText('Pine chair')).closest('li') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(screen.queryByText('Pine chair')).toBeNull());
  });

  it('shows an alert and reloads the cart when a change fails', async () => {
    const fetchMock = stubShop(customerMe, () => jsonResponse(twoLines), {
      '/api/cart/lines/pine-chair': () => problemResponse(503, 'Busy'),
    });
    renderApp('/cart');

    const row = (await screen.findByText('Pine chair')).closest('li') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: 'Remove' }));

    expect((await screen.findByRole('alert')).textContent).toBe('Busy');
    await waitFor(() => expect(urlsRequested(fetchMock, '/api/cart').length).toBeGreaterThan(1));
  });

  it('asks anonymous visitors to sign in', async () => {
    stubShop(anonymousMe, () => problemResponse(401));
    renderApp('/cart');

    expect(await screen.findByText('You need to sign in to shop.')).toBeTruthy();
  });

  it('tells users without the customer role they have no access', async () => {
    stubShop(customerMe, () => problemResponse(403));
    renderApp('/cart');

    expect(await screen.findByText('This area is only for customers.')).toBeTruthy();
  });

  it('shows an alert when the cart cannot be loaded', async () => {
    stubShop(customerMe, () => problemResponse(500));
    renderApp('/cart');

    expect((await screen.findByRole('alert')).textContent).toBe('The cart could not be loaded.');
  });

  it('shows a loading message', async () => {
    stubShop(customerMe, () => new Promise<Response>(() => undefined));
    renderApp('/cart');

    expect(await screen.findByText('Loading cart')).toBeTruthy();
  });
});

describe('product page purchase controls', () => {
  const product: Record<string, Responder> = {
    '/api/products/oak-chair': () => jsonResponse(oakChairDetail),
  };

  it('offers sign in to anonymous visitors', async () => {
    stubShop(anonymousMe, () => problemResponse(401), product);
    renderApp('/products/oak-chair');

    expect((await screen.findByRole('link', { name: 'Sign in to buy' })).getAttribute('href')).toBe(
      '/oauth2/authorization/keycloak',
    );
    expect(screen.queryByRole('button', { name: 'Add to cart' })).toBeNull();
  });

  it('adds the chosen quantity and confirms', async () => {
    const bodies: unknown[] = [];
    stubShop(customerMe, () => jsonResponse(cartOf([], 0)), {
      ...product,
      '/api/cart/lines/oak-chair': async (_url, request) => {
        bodies.push(await request.json());
        return jsonResponse(cartOf([cartLine('oak-chair', 'Oak chair', 3)]));
      },
    });
    renderApp('/products/oak-chair');

    fireEvent.change(await screen.findByLabelText('Quantity'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to cart' }));

    expect((await screen.findByRole('status')).textContent).toBe('Added to cart');
    expect(bodies).toEqual([{ quantity: 3 }]);
    expect(await screen.findByRole('link', { name: 'Cart (3)' })).toBeTruthy();
  });

  it.each([
    [404, 'tag:shop.dev,2026:not-found', 'This product is no longer available.'],
    [409, 'tag:shop.dev,2026:cart-full', 'Your cart is full. Remove an item first.'],
    [500, 'tag:shop.dev,2026:internal', 'The product could not be added. Try again.'],
  ])('explains a %d answer', async (status, type, message) => {
    stubShop(customerMe, () => jsonResponse(cartOf([], 0)), {
      ...product,
      '/api/cart/lines/oak-chair': () => jsonResponse({ type, status, errors: [] }, status),
    });
    renderApp('/products/oak-chair');

    fireEvent.click(await screen.findByRole('button', { name: 'Add to cart' }));

    expect((await screen.findByRole('alert')).textContent).toBe(message);
  });
});
