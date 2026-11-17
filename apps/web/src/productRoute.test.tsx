import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { categories, oakChair, oakChairDetail, productPage } from '@/test/fixtures';
import { jsonResponse, problemResponse, stubFetch, type Responder } from '@/test/fetchStub';
import { renderApp } from '@/test/renderApp';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function stubProduct(product: Responder) {
  return stubFetch({
    '/api/products/oak-chair': product,
    '/api/categories': () => jsonResponse(categories),
    '/api/products': () => jsonResponse(productPage([oakChair])),
  });
}

describe('product route', () => {
  it('shows the product with its price, category and description', async () => {
    stubProduct(() => jsonResponse(oakChairDetail));
    renderApp('/products/oak-chair');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Oak chair');
    expect(screen.getByText('€129.99')).toBeTruthy();
    expect(screen.getByText('Chairs')).toBeTruthy();
    expect(screen.getByText('A sturdy chair made of solid oak.')).toBeTruthy();
    expect(document.title).toBe('Oak chair | Shop');
  });

  it('links back to the catalog', async () => {
    stubProduct(() => jsonResponse(oakChairDetail));
    const router = renderApp('/products/oak-chair');

    fireEvent.click(await screen.findByRole('link', { name: 'Back to products' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Products');
  });

  it('shows a not found page for an unknown product', async () => {
    stubProduct(() => problemResponse(404, 'Not Found'));
    renderApp('/products/oak-chair');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe(
      'Product not found',
    );
    expect(document.title).toBe('Product not found | Shop');
    expect(screen.getByRole('link', { name: 'Back to products' })).toBeTruthy();
  });

  it('shows not found for a slug the API rejects as invalid', async () => {
    stubProduct(() => problemResponse(400, 'Bad Request'));
    renderApp('/products/oak-chair');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe(
      'Product not found',
    );
  });

  it('shows an error with a retry for a server failure', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    let calls = 0;
    stubProduct(() => {
      calls += 1;
      return calls === 1 ? problemResponse(500) : jsonResponse(oakChairDetail);
    });
    renderApp('/products/oak-chair');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe(
      'Something went wrong',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Oak chair'),
    );
  });

  it('shows a loading message while the product is pending', async () => {
    let release: () => void = () => undefined;
    stubProduct(
      () =>
        new Promise((resolve) => {
          release = () => resolve(jsonResponse(oakChairDetail));
        }),
    );
    renderApp('/products/oak-chair');

    expect((await screen.findByRole('status')).textContent).toBe('Loading product');
    release();
    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Oak chair');
  });
});
