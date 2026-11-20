import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { components } from '@/api/schema';
import { adminProduct, adminProductPage } from '@/test/fixtures';
import { jsonResponse, urlsRequested } from '@/test/fetchStub';
import { renderApp } from '@/test/renderApp';
import {
  CONFLICT,
  DUPLICATE,
  VALIDATION,
  bodyOf,
  problem,
  stubAdminApi,
  type,
} from '@/test/adminApi';

type AdminProduct = components['schemas']['AdminProduct'];

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function fillValidProduct() {
  type('Slug', 'pine-desk');
  type('Name', 'Pine desk');
  type('Description', 'A desk.\nWith drawers.');
  type('Price (EUR)', '12.3');
  type('Category', 'lamps');
}

const created: AdminProduct = {
  ...adminProduct(9, 'Pine desk'),
  slug: 'pine-desk',
  category: { slug: 'lamps', name: 'Lamps' },
  active: false,
  version: 0,
  stock: { onHand: 0, reserved: 0, version: 0 },
};

describe('new product page', () => {
  it('shows the form with its labelled controls', async () => {
    stubAdminApi();
    renderApp('/admin/products/new');

    expect(await screen.findByRole('heading', { level: 1, name: 'New product' })).toBeTruthy();
    await screen.findByLabelText('Slug');
    for (const label of ['Slug', 'Name', 'Description', 'Price (EUR)', 'Category', 'Active']) {
      expect(screen.getByLabelText(label)).toBeTruthy();
    }
    expect(screen.getByRole('button', { name: 'Create product' })).toBeTruthy();
    expect((screen.getByLabelText('Slug') as HTMLInputElement).readOnly).toBe(false);
    expect((screen.getByLabelText('Active') as HTMLInputElement).checked).toBe(false);
    expect(document.title).toBe('New product | Shop');
    expect(
      within(screen.getByLabelText('Category'))
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['Choose a category', 'Chairs', 'Lamps']);
  });

  it('waits for the categories', async () => {
    stubAdminApi({ '/api/categories': () => new Promise<Response>(() => undefined) });
    renderApp('/admin/products/new');

    expect((await screen.findByRole('status')).textContent).toBe('Loading categories');
  });

  it('says when the categories fail to load', async () => {
    stubAdminApi({ '/api/categories': () => new Response(null, { status: 500 }) });
    renderApp('/admin/products/new');

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The categories could not be loaded.',
    );
  });

  it('shows client errors, sends nothing and focuses the first invalid field', async () => {
    const fetchMock = stubAdminApi();
    renderApp('/admin/products/new');
    await screen.findByLabelText('Slug');

    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));

    expect(await screen.findByText('Enter a slug')).toBeTruthy();
    const slug = screen.getByLabelText('Slug');
    expect(slug.getAttribute('aria-invalid')).toBe('true');
    expect(slug.getAttribute('aria-describedby')).toBe('slug-error');
    expect(screen.getByText('Enter a name')).toBeTruthy();
    expect(screen.getByText('Enter a description')).toBeTruthy();
    expect(screen.getByText('Enter a price such as 12.34')).toBeTruthy();
    expect(screen.getByText('Choose a category', { selector: 'p' })).toBeTruthy();
    await waitFor(() => expect(document.activeElement).toBe(slug));
    expect(await bodyOf(fetchMock, 'POST', '/api/admin/products')).toEqual([]);
    expect(screen.getByLabelText('Name').getAttribute('aria-describedby')).toBe('name-error');
  });

  it('flags a bad slug as the visitor types', async () => {
    stubAdminApi();
    renderApp('/admin/products/new');
    await screen.findByLabelText('Slug');

    type('Slug', 'Bad Slug');

    expect(
      await screen.findByText('Use lowercase letters and digits separated by single hyphens'),
    ).toBeTruthy();
  });

  it('creates the product, then opens its edit page with a saved message that shows once', async () => {
    const fetchMock = stubAdminApi({
      '/api/admin/products': (_url, request) =>
        request.method === 'POST'
          ? jsonResponse(created, 201)
          : jsonResponse(adminProductPage([created])),
      '/api/admin/products/9': () => jsonResponse(created),
    });
    const router = renderApp('/admin/products/new');
    await screen.findByLabelText('Slug');

    fillValidProduct();
    fireEvent.click(screen.getByLabelText('Active'));
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Edit product' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/admin/products/9');
    expect((await screen.findByRole('status')).textContent).toBe('Product saved');
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    expect(screen.getByText('Product saved')).toBeTruthy();
    expect(await bodyOf(fetchMock, 'POST', '/api/admin/products')).toEqual([
      {
        slug: 'pine-desk',
        name: 'Pine desk',
        description: 'A desk.\nWith drawers.',
        priceMinor: 1230,
        categorySlug: 'lamps',
        active: true,
      },
    ]);
  });

  it('shows a duplicate slug under the Slug field and clears it on change', async () => {
    stubAdminApi({
      '/api/admin/products': () =>
        problem(409, DUPLICATE, [{ field: 'slug', message: 'A product with this slug exists' }]),
    });
    renderApp('/admin/products/new');
    await screen.findByLabelText('Slug');
    fillValidProduct();

    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));

    expect(await screen.findByText('A product with this slug exists')).toBeTruthy();
    expect(screen.getByLabelText('Slug').getAttribute('aria-invalid')).toBe('true');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Slug')));

    type('Slug', 'pine-desk-2');

    await waitFor(() => expect(screen.queryByText('A product with this slug exists')).toBeNull());
    expect(screen.getByLabelText('Slug').getAttribute('aria-invalid')).toBe('false');
  });

  it('maps server field errors and sends unknown ones to the alert', async () => {
    stubAdminApi({
      '/api/admin/products': () =>
        problem(400, VALIDATION, [
          { field: 'priceMinor', message: 'Price is too high' },
          { field: 'categorySlug', message: 'Unknown category' },
          { field: 'stock', message: 'Odd stock' },
        ]),
    });
    renderApp('/admin/products/new');
    await screen.findByLabelText('Slug');
    fillValidProduct();

    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));

    expect(await screen.findByText('Price is too high')).toBeTruthy();
    expect(screen.getByText('Unknown category')).toBeTruthy();
    expect((await screen.findByRole('alert')).textContent).toBe('Odd stock');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('alert')));
    expect(screen.queryByRole('button', { name: 'Reload' })).toBeNull();
  });

  it.each(['Your access cannot be confirmed right now', 'The product is busy; try again shortly'])(
    'shows the API detail %j for a 503, resubmits nothing and offers no reload',
    async (detail) => {
      const fetchMock = stubAdminApi({
        '/api/admin/products': () => problem(503, 'about:blank', [], detail),
      });
      renderApp('/admin/products/new');
      await screen.findByLabelText('Slug');
      fillValidProduct();

      fireEvent.click(screen.getByRole('button', { name: 'Create product' }));

      expect((await screen.findByRole('alert')).textContent).toBe(detail);
      expect(await bodyOf(fetchMock, 'POST', '/api/admin/products')).toHaveLength(1);
    },
  );

  it('shows a generic message when the server fails or cannot be reached', async () => {
    let attempt = 0;
    stubAdminApi({
      '/api/admin/products': (_url, request) => {
        if (request.method !== 'POST') return jsonResponse(adminProductPage([]));
        attempt += 1;
        if (attempt === 1) return new Response('boom', { status: 500 });
        throw new TypeError('offline');
      },
    });
    renderApp('/admin/products/new');
    await screen.findByLabelText('Slug');
    fillValidProduct();

    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
    expect((await screen.findByRole('alert')).textContent).toBe(
      'The change could not be saved. Try again.',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toBe(
        'The request could not be sent. Check your connection and try again.',
      ),
    );
  });

  it('shows the conflict message without a reload button when a create reports one', async () => {
    stubAdminApi({ '/api/admin/products': () => problem(409, CONFLICT) });
    renderApp('/admin/products/new');
    await screen.findByLabelText('Slug');
    fillValidProduct();

    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'This product was changed by someone else.',
    );
    expect(screen.queryByRole('button', { name: 'Reload' })).toBeNull();
  });

  it('disables the button while the request is pending', async () => {
    const fetchMock = stubAdminApi({
      '/api/admin/products': (_url, request) =>
        request.method === 'POST'
          ? new Promise<Response>(() => undefined)
          : jsonResponse(adminProductPage([])),
    });
    renderApp('/admin/products/new');
    await screen.findByLabelText('Slug');
    fillValidProduct();

    fireEvent.click(screen.getByRole('button', { name: 'Create product' }));

    await waitFor(() => expect(urlsRequested(fetchMock, '/api/admin/products')).toHaveLength(1));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Create product' }).matches(':disabled')).toBe(
        true,
      ),
    );
  });
});

describe('admin navigation', () => {
  it('links to products and the audit log, and the list links to new and edit pages', async () => {
    stubAdminApi({
      '/api/admin/audit': () =>
        jsonResponse({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }),
    });
    const router = renderApp('/admin');

    const nav = await screen.findByRole('navigation', { name: 'Admin' });
    expect(within(nav).getByRole('link', { name: 'Products' }).getAttribute('href')).toBe('/admin');
    expect(await screen.findByRole('link', { name: 'Oak chair' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Oak chair' }).getAttribute('href')).toBe(
      '/admin/products/7',
    );
    expect(screen.getByRole('columnheader', { name: 'Stock' })).toBeTruthy();
    expect(screen.getByText('5')).toBeTruthy();

    fireEvent.click(screen.getByRole('link', { name: 'New product' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'New product' })).toBeTruthy();

    fireEvent.click(within(nav).getByRole('link', { name: 'Audit log' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Audit log' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/admin/audit');
  });
});
