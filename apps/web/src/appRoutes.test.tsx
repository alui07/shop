import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { categories, oakChair, productPage } from '@/test/fixtures';
import { jsonResponse, stubFetch } from '@/test/fetchStub';
import { renderApp } from '@/test/renderApp';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function stubShop() {
  stubFetch({
    '/api/status': () => jsonResponse({ status: 'ok', version: '1.2.3' }),
    '/api/categories': () => jsonResponse(categories),
    '/api/products': () => jsonResponse(productPage([oakChair])),
  });
}

describe('status route', () => {
  it('shows the API status at /status', async () => {
    stubShop();
    renderApp('/status');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('API status');
    expect((await screen.findByText('1.2.3')).tagName).toBe('DD');
    expect(document.title).toBe('API status | Shop');
  });
});

describe('unknown routes', () => {
  it('shows a page not found message', async () => {
    stubShop();
    renderApp('/nowhere');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Page not found');
    expect(document.title).toBe('Page not found | Shop');
  });
});

describe('site navigation', () => {
  it('moves between the catalog and the status page and marks the current link', async () => {
    stubShop();
    const router = renderApp('/');
    const main = await screen.findByRole('navigation', { name: 'Main' });

    expect(screen.getByRole('link', { name: 'Products' }).getAttribute('aria-current')).toBe(
      'page',
    );
    expect(main).toBeTruthy();
    fireEvent.click(screen.getByRole('link', { name: 'Status' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/status'));
    expect(screen.getByRole('link', { name: 'Products' }).getAttribute('aria-current')).toBeNull();

    fireEvent.click(screen.getByRole('link', { name: 'Shop' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });
});
