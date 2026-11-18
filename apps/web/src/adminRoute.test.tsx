import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { adminMe, adminProduct, adminProductPage, anonymousMe, customerMe } from '@/test/fixtures';
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

function stubAdmin(me: unknown, products: Responder = () => jsonResponse(adminProductPage([]))) {
  return stubFetch({
    '/api/me': () => jsonResponse(me),
    '/api/admin/products': products,
  });
}

const twoProducts = () =>
  jsonResponse(
    adminProductPage([adminProduct(1, 'Oak chair'), adminProduct(2, 'Old lamp', false)]),
  );

describe('admin layout', () => {
  it('shows a loading message while the session is being read', async () => {
    stubFetch({ '/api/me': () => new Promise<Response>(() => undefined) });
    renderApp('/admin');

    expect((await screen.findByRole('status')).textContent).toBe('Loading');
  });

  it('asks anonymous visitors to sign in without calling the admin API', async () => {
    const fetchMock = stubAdmin(anonymousMe);
    renderApp('/admin');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('Sign in');
    expect(
      within(screen.getByRole('main')).getByRole('link', { name: 'Sign in' }).getAttribute('href'),
    ).toBe('/oauth2/authorization/keycloak');
    expect(document.title).toBe('Sign in | Shop');
    expect(urlsRequested(fetchMock, '/api/admin/products')).toEqual([]);
  });

  it('tells customers they have no access without calling the admin API', async () => {
    const fetchMock = stubAdmin(customerMe);
    renderApp('/admin');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe(
      'You do not have access',
    );
    expect(document.title).toBe('No access | Shop');
    expect(urlsRequested(fetchMock, '/api/admin/products')).toEqual([]);
  });
});

describe('admin products', () => {
  it('lists products with their category, price and active state', async () => {
    stubAdmin(adminMe, twoProducts);
    renderApp('/admin');

    const rows = await screen.findAllByRole('row');

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Admin products');
    expect(document.title).toBe('Admin products | Shop');
    expect(rows).toHaveLength(3);
    const first = within(rows[1] as HTMLElement);
    expect(first.getByText('Oak chair')).toBeTruthy();
    expect(first.getByText('oak-chair')).toBeTruthy();
    expect(first.getByText('Chairs')).toBeTruthy();
    expect(first.getByText('€129.99')).toBeTruthy();
    expect(first.getByText('Active')).toBeTruthy();
    expect(within(rows[2] as HTMLElement).getByText('Inactive')).toBeTruthy();
  });

  it('shows a loading message first', async () => {
    stubAdmin(adminMe, () => new Promise<Response>(() => undefined));
    renderApp('/admin');

    expect((await screen.findByRole('status')).textContent).toBe('Loading products');
  });

  it('says when nothing matches', async () => {
    stubAdmin(adminMe);
    renderApp('/admin');

    expect(await screen.findByText('No products match')).toBeTruthy();
  });

  it('searches and drops the page', async () => {
    const fetchMock = stubAdmin(adminMe, twoProducts);
    const router = renderApp('/admin?page=2');
    await screen.findAllByRole('row');

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: ' oak ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'oak' }));
    await waitFor(() =>
      expect(urlsRequested(fetchMock, '/api/admin/products').map((url) => url.search)).toContain(
        '?q=oak',
      ),
    );
  });

  it('clears the search when the box is emptied', async () => {
    stubAdmin(adminMe, twoProducts);
    const router = renderApp('/admin?q=oak');
    await screen.findAllByRole('row');

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(router.state.location.search).toEqual({}));
  });

  it('pages with 1-based URLs and 0-based API pages', async () => {
    const fetchMock = stubAdmin(adminMe, () =>
      jsonResponse(adminProductPage([adminProduct(1, 'Oak chair')], { totalPages: 3 })),
    );
    const router = renderApp('/admin');
    await screen.findAllByRole('row');

    fireEvent.click(screen.getByRole('link', { name: 'Next' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin'));
    await waitFor(() => expect(router.state.location.search).toEqual({ page: '2' }));
    await waitFor(() =>
      expect(urlsRequested(fetchMock, '/api/admin/products').map((url) => url.search)).toContain(
        '?page=1',
      ),
    );
  });

  it('shows no pagination for a single page', async () => {
    stubAdmin(adminMe, twoProducts);
    renderApp('/admin');
    await screen.findAllByRole('row');

    expect(screen.queryByRole('navigation', { name: 'Pagination' })).toBeNull();
  });
});

describe('admin API failures', () => {
  it('asks for a new sign-in when the session has ended', async () => {
    stubAdmin(adminMe, () => problemResponse(401));
    renderApp('/admin');

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeTruthy();
  });

  it('shows no access when the API forbids the call', async () => {
    stubAdmin(adminMe, () => problemResponse(403));
    renderApp('/admin');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'You do not have access' }),
    ).toBeTruthy();
  });

  it('shows an error for any other failure', async () => {
    stubAdmin(adminMe, () => problemResponse(500));
    renderApp('/admin');

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The products could not be loaded.',
    );
  });
});
