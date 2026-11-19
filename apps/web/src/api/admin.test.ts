import { QueryClient } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { adminProduct } from '@/test/fixtures';
import { jsonResponse, stubFetch } from '@/test/fetchStub';
import { apiClient } from './client';
import { createApiMiddleware } from './middleware';
import {
  AdminProblemError,
  adminProductQuery,
  createProduct,
  refreshAfterSave,
  setStock,
  updateProduct,
} from './admin';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

const body = {
  name: 'n',
  description: 'd',
  priceMinor: 1,
  categorySlug: 'c',
  active: true,
  version: 0,
};

describe('writes', () => {
  it('send the CSRF header on POST and PUT', async () => {
    vi.spyOn(document, 'cookie', 'get').mockReturnValue('__Host-XSRF-TOKEN=tok');
    const middleware = createApiMiddleware(new QueryClient());
    apiClient.use(middleware);
    const fetchMock = stubFetch({
      '/api/admin/products': () => jsonResponse(adminProduct(7, 'Oak chair'), 201),
      '/api/admin/products/7': () => jsonResponse(adminProduct(7, 'Oak chair')),
      '/api/admin/products/7/stock': () => jsonResponse(adminProduct(7, 'Oak chair')),
    });

    await createProduct({ ...body, slug: 's' });
    await updateProduct(7, body);
    await setStock(7, { onHand: 1, version: 0 });
    apiClient.eject(middleware);

    expect(fetchMock.mock.calls.map(([request]) => request.method)).toEqual(['POST', 'PUT', 'PUT']);
    expect(fetchMock.mock.calls.map(([request]) => request.headers.get('X-XSRF-TOKEN'))).toEqual([
      'tok',
      'tok',
      'tok',
    ]);
  });

  it('throw the parsed problem when the API rejects them', async () => {
    stubFetch({
      '/api/admin/products/7': () =>
        jsonResponse(
          { type: 'tag:shop.dev,2026:validation', errors: [{ field: 'name', message: 'blank' }] },
          400,
        ),
    });

    const failure = await updateProduct(7, body).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(AdminProblemError);
    expect((failure as AdminProblemError).status).toBe(400);
    expect((failure as AdminProblemError).problem.errors).toEqual([
      { field: 'name', message: 'blank' },
    ]);
  });

  it('throw an empty problem for an unreadable body', async () => {
    stubFetch({ '/api/admin/products/7': () => new Response('<html>', { status: 502 }) });

    const failure = (await updateProduct(7, body).catch(
      (error: unknown) => error,
    )) as AdminProblemError;

    expect([failure.status, failure.problem]).toEqual([502, { errors: [] }]);
  });

  it('throw status 0 when the request gets no answer', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new TypeError('offline')));

    const failure = (await updateProduct(7, body).catch(
      (error: unknown) => error,
    )) as AdminProblemError;

    expect([failure.status, failure.problem]).toEqual([0, { errors: [] }]);
  });
});

describe('refreshAfterSave', () => {
  it('stores the product and invalidates admin, catalog and product queries', () => {
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const product = adminProduct(7, 'Oak chair');

    refreshAfterSave(queryClient, product);

    expect(queryClient.getQueryData(adminProductQuery(7).queryKey)).toEqual(product);
    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ['admin'],
      ['products'],
      ['product', 'oak-chair'],
    ]);
  });
});
