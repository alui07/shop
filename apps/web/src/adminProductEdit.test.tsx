import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CONFLICT, VALIDATION, bodyOf, problem, stubAdminApi, type } from '@/test/adminApi';
import { adminProduct } from '@/test/fixtures';
import { jsonResponse, urlsRequested } from '@/test/fetchStub';
import { renderApp } from '@/test/renderApp';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('edit product page', () => {
  it('fills the form from the product and locks the slug', async () => {
    stubAdminApi();
    renderApp('/admin/products/7');

    expect(await screen.findByRole('heading', { level: 1, name: 'Edit product' })).toBeTruthy();
    expect(((await screen.findByLabelText('Name')) as HTMLInputElement).value).toBe('Oak chair');
    expect((screen.getByLabelText('Slug') as HTMLInputElement).value).toBe('oak-chair');
    expect((screen.getByLabelText('Slug') as HTMLInputElement).readOnly).toBe(true);
    expect((screen.getByLabelText('Description') as HTMLTextAreaElement).value).toBe(
      'Oak chair description',
    );
    expect((screen.getByLabelText('Price (EUR)') as HTMLInputElement).value).toBe('129.99');
    expect((screen.getByLabelText('Category') as HTMLSelectElement).value).toBe('chairs');
    expect((screen.getByLabelText('Active') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText('On hand') as HTMLInputElement).value).toBe('5');
    expect(screen.getByText('Reserved: 2')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save stock' })).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
    expect(document.title).toBe('Edit product | Shop');
  });

  it('shows a loading message first', async () => {
    stubAdminApi({ '/api/admin/products/7': () => new Promise<Response>(() => undefined) });
    renderApp('/admin/products/7');

    expect((await screen.findByRole('status')).textContent).toBe('Loading product');
  });

  it.each([404, 400])('shows the not-found panel for a %d', async (status) => {
    stubAdminApi({ '/api/admin/products/7': () => problem(status, 'about:blank') });
    renderApp('/admin/products/7');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Product not found' }),
    ).toBeTruthy();
  });

  it.each(['0', 'abc', '1.5'])(
    'shows the not-found panel for the id %s without a request',
    async (id) => {
      const fetchMock = stubAdminApi();
      renderApp(`/admin/products/${id}`);

      expect(
        await screen.findByRole('heading', { level: 1, name: 'Product not found' }),
      ).toBeTruthy();
      expect(urlsRequested(fetchMock, `/api/admin/products/${id}`)).toEqual([]);
    },
  );

  it('asks for a new sign-in on 401', async () => {
    stubAdminApi({ '/api/admin/products/7': () => problem(401, 'about:blank') });
    renderApp('/admin/products/7');

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeTruthy();
  });

  it('shows no access on 403', async () => {
    stubAdminApi({ '/api/admin/products/7': () => problem(403, 'about:blank') });
    renderApp('/admin/products/7');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'You do not have access' }),
    ).toBeTruthy();
  });

  it('shows an error for any other failure', async () => {
    stubAdminApi({ '/api/admin/products/7': () => problem(500, 'about:blank') });
    renderApp('/admin/products/7');

    expect((await screen.findByRole('alert')).textContent).toBe('The product could not be loaded.');
  });

  it('sends version n then n+1 for two saves on one mounted form', async () => {
    let served = adminProduct(7, 'Oak chair');
    const fetchMock = stubAdminApi({
      '/api/admin/products/7': (_url, request) => {
        if (request.method === 'PUT') served = { ...served, version: served.version + 1 };
        return jsonResponse(served);
      },
    });
    renderApp('/admin/products/7');
    await screen.findByLabelText('Name');

    type('Name', 'Oak chair 2');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect((await screen.findByText('Product saved')).getAttribute('role')).toBe('status');
    type('Name', 'Oak chair 3');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() =>
      expect(bodyOf(fetchMock, 'PUT', '/api/admin/products/7')).resolves.toHaveLength(2),
    );

    const bodies = await bodyOf(fetchMock, 'PUT', '/api/admin/products/7');
    expect(bodies.map((body) => body['version'])).toEqual([3, 4]);
    expect(bodies[0]).toEqual({
      name: 'Oak chair 2',
      description: 'Oak chair description',
      priceMinor: 12999,
      categorySlug: 'chairs',
      active: true,
      version: 3,
    });
    expect(bodies[1]?.['name']).toBe('Oak chair 3');
  });

  it('keeps product and stock versions apart when saving one after the other', async () => {
    const fetchMock = stubAdminApi({
      '/api/admin/products/7': (_url, request) =>
        request.method === 'GET'
          ? jsonResponse(adminProduct(7, 'Oak chair'))
          : jsonResponse({ ...adminProduct(7, 'Oak chair'), version: 4 }),
      '/api/admin/products/7/stock': () =>
        jsonResponse({
          ...adminProduct(7, 'Oak chair'),
          stock: { onHand: 9, reserved: 2, version: 8 },
        }),
    });
    renderApp('/admin/products/7');
    await screen.findByLabelText('Name');

    type('On hand', '9');
    fireEvent.click(screen.getByRole('button', { name: 'Save stock' }));
    expect(await screen.findByText('Stock saved')).toBeTruthy();
    type('Name', 'Oak chair 2');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Product saved')).toBeTruthy();
    type('On hand', '10');
    fireEvent.click(screen.getByRole('button', { name: 'Save stock' }));
    await waitFor(() =>
      expect(bodyOf(fetchMock, 'PUT', '/api/admin/products/7/stock')).resolves.toHaveLength(2),
    );

    expect(await bodyOf(fetchMock, 'PUT', '/api/admin/products/7/stock')).toEqual([
      { onHand: 9, version: 7 },
      { onHand: 10, version: 8 },
    ]);
    expect((await bodyOf(fetchMock, 'PUT', '/api/admin/products/7'))[0]?.['version']).toBe(3);
  });

  it('refreshes the admin list and the public pages after a save', async () => {
    const fetchMock = stubAdminApi({});
    renderApp('/admin/products/7');
    await screen.findByLabelText('Name');
    const before = urlsRequested(fetchMock, '/api/admin/products/7').length;

    type('Name', 'Oak chair 2');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await screen.findByText('Product saved');
    await waitFor(() =>
      expect(urlsRequested(fetchMock, '/api/admin/products/7').length).toBeGreaterThan(before + 1),
    );
  });

  it('shows server field errors for the stock form', async () => {
    stubAdminApi({
      '/api/admin/products/7/stock': () =>
        problem(400, VALIDATION, [{ field: 'onHand', message: 'On hand is below reserved' }]),
    });
    renderApp('/admin/products/7');
    await screen.findByLabelText('On hand');

    type('On hand', '1');
    fireEvent.click(screen.getByRole('button', { name: 'Save stock' }));

    expect(await screen.findByText('On hand is below reserved')).toBeTruthy();
    expect(screen.getByLabelText('On hand').getAttribute('aria-invalid')).toBe('true');
  });

  it('rejects an invalid stock count without a request', async () => {
    const fetchMock = stubAdminApi();
    renderApp('/admin/products/7');
    await screen.findByLabelText('On hand');

    type('On hand', 'abc');
    fireEvent.click(screen.getByRole('button', { name: 'Save stock' }));

    expect(await screen.findByText('Enter a whole number')).toBeTruthy();
    expect(await bodyOf(fetchMock, 'PUT', '/api/admin/products/7/stock')).toEqual([]);
    expect((await screen.findByLabelText('Name')).getAttribute('aria-invalid')).toBe('false');
  });

  it('offers a reload after a product conflict and refills the forms from the server', async () => {
    let served = adminProduct(7, 'Oak chair');
    stubAdminApi({
      '/api/admin/products/7': (_url, request) =>
        request.method === 'PUT' ? problem(409, CONFLICT) : jsonResponse(served),
    });
    renderApp('/admin/products/7');
    await screen.findByLabelText('Name');
    type('Name', 'My edit');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect((await screen.findByRole('alert')).textContent).toContain(
      'This product was changed by someone else.',
    );
    served = { ...adminProduct(7, 'Oak chair from elsewhere'), version: 4 };
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));

    await waitFor(() =>
      expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe(
        'Oak chair from elsewhere',
      ),
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('does not show the created message again after a reload', async () => {
    stubAdminApi({
      '/api/admin/products/7': (_url, request) =>
        request.method === 'PUT'
          ? problem(409, CONFLICT)
          : jsonResponse(adminProduct(7, 'Oak chair')),
    });
    renderApp('/admin/products/7?saved=true');
    expect(await screen.findByText('Product saved')).toBeTruthy();
    type('Name', 'My edit');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByRole('alert');

    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));

    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(screen.queryByText('Product saved')).toBeNull();
  });

  it('offers a reload after a stock conflict', async () => {
    let served = adminProduct(7, 'Oak chair');
    stubAdminApi({
      '/api/admin/products/7': () => jsonResponse(served),
      '/api/admin/products/7/stock': () => problem(409, CONFLICT),
    });
    renderApp('/admin/products/7');
    await screen.findByLabelText('On hand');
    type('On hand', '8');
    fireEvent.click(screen.getByRole('button', { name: 'Save stock' }));
    await screen.findByRole('alert');
    served = { ...served, stock: { onHand: 11, reserved: 2, version: 9 } };

    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));

    await waitFor(() =>
      expect((screen.getByLabelText('On hand') as HTMLInputElement).value).toBe('11'),
    );
  });

  it('refetches after a server failure and never resubmits by itself', async () => {
    const fetchMock = stubAdminApi({
      '/api/admin/products/7': (_url, request) =>
        request.method === 'PUT'
          ? problem(503, 'about:blank')
          : jsonResponse(adminProduct(7, 'Oak chair')),
      '/api/admin/products/7/stock': () => problem(500, 'about:blank'),
    });
    renderApp('/admin/products/7');
    await screen.findByLabelText('Name');
    const loads = () =>
      fetchMock.mock.calls.filter(
        ([call]) => call.method === 'GET' && new URL(call.url).pathname === '/api/admin/products/7',
      ).length;
    const initialLoads = loads();

    type('Name', 'Oak chair 2');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByRole('alert');
    await waitFor(() => expect(loads()).toBe(initialLoads + 1));

    type('On hand', '3');
    fireEvent.click(screen.getByRole('button', { name: 'Save stock' }));
    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(2));
    await waitFor(() => expect(loads()).toBe(initialLoads + 2));
    expect(await bodyOf(fetchMock, 'PUT', '/api/admin/products/7')).toHaveLength(1);
    expect(await bodyOf(fetchMock, 'PUT', '/api/admin/products/7/stock')).toHaveLength(1);
  });

  describe('while a save is in flight', () => {
    async function savedWhileLocked(button: string, label: string, typed: string, saved: string) {
      let release: () => void = () => undefined;
      const gate = new Promise<void>((resolve) => (release = resolve));
      let served = adminProduct(7, 'Oak chair');
      stubAdminApi({
        '/api/admin/products/7': async (_url, request) => {
          if (request.method === 'PUT') {
            await gate;
            served = { ...served, version: 4, price: { amountMinor: 1500, currency: 'EUR' } };
          }
          return jsonResponse(served);
        },
        '/api/admin/products/7/stock': async () => {
          await gate;
          served = { ...served, stock: { ...served.stock, onHand: 9, version: 8 } };
          return jsonResponse(served);
        },
      });
      renderApp('/admin/products/7');
      await screen.findByLabelText(label);
      type(label, typed);
      fireEvent.click(screen.getByRole('button', { name: button }));
      const input = () => screen.getByLabelText(label) as HTMLInputElement;

      await waitFor(() => expect(input().matches(':disabled')).toBe(true));
      release();
      expect(await screen.findByRole('status')).toBeTruthy();

      expect(input().matches(':disabled')).toBe(false);
      expect(input().value).toBe(saved);
      type(label, '16.50');
      expect(input().value).toBe('16.50');
    }

    it('locks the product inputs until the saved values are back in the form', async () => {
      await savedWhileLocked('Save changes', 'Price (EUR)', '15', '15.00');
    });

    it('locks the stock input until the saved values are back in the form', async () => {
      await savedWhileLocked('Save stock', 'On hand', '9', '9');
    });
  });
});
