import { queryOptions, type QueryClient } from '@tanstack/react-query';
import type { AdminSearch } from '@/lib/adminSearch';
import { problemSchema, type Problem } from '@/lib/problem';
import type { components } from './schema';
import { unwrap } from './catalog';
import { apiClient } from './client';
import { parseRetryAfter } from './problemError';

type AdminProduct = components['schemas']['AdminProduct'];
type ProductInput = components['schemas']['ProductInput'];
type ProductUpdateInput = components['schemas']['ProductUpdateInput'];
type StockInput = components['schemas']['StockInput'];

/** A rejected write; `status` is 0 when the request never got an answer. */
export class AdminProblemError extends Error {
  readonly status: number;
  readonly problem: Problem;
  /** Seconds from a delta-seconds `Retry-After` header; absent for an HTTP-date or invalid value. */
  readonly retryAfterSeconds?: number;

  constructor(status: number, problem: Problem, retryAfterSeconds?: number) {
    super(`Admin request failed with status ${status}`);
    this.name = 'AdminProblemError';
    this.status = status;
    this.problem = problem;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export function adminProductsQuery(search: AdminSearch) {
  return queryOptions({
    queryKey: ['admin', 'products', search],
    queryFn: async ({ signal }) =>
      unwrap(
        await apiClient.GET('/api/admin/products', {
          params: {
            query: {
              q: search.q,
              page: search.page === undefined ? undefined : search.page - 1,
            },
          },
          signal,
        }),
      ),
  });
}

export function adminProductQuery(id: number) {
  return queryOptions({
    queryKey: ['admin', 'product', id],
    queryFn: async ({ signal }) =>
      unwrap(await apiClient.GET('/api/admin/products/{id}', { params: { path: { id } }, signal })),
  });
}

export function auditQuery(page: number | undefined) {
  return queryOptions({
    queryKey: ['admin', 'audit', page],
    queryFn: async ({ signal }) =>
      unwrap(
        await apiClient.GET('/api/admin/audit', {
          params: { query: { page: page === undefined ? undefined : page - 1 } },
          signal,
        }),
      ),
  });
}

async function send(
  call: Promise<{ data?: AdminProduct; error?: unknown; response: Response }>,
): Promise<AdminProduct> {
  let result;
  try {
    result = await call;
  } catch {
    throw new AdminProblemError(0, { errors: [] });
  }
  if (result.data === undefined) {
    const parsed = problemSchema.safeParse(result.error);
    throw new AdminProblemError(
      result.response.status,
      parsed.success ? parsed.data : { errors: [] },
      parseRetryAfter(result.response.headers.get('Retry-After')),
    );
  }
  return result.data;
}

export function createProduct(body: ProductInput): Promise<AdminProduct> {
  return send(apiClient.POST('/api/admin/products', { body }));
}

export function updateProduct(id: number, body: ProductUpdateInput): Promise<AdminProduct> {
  return send(apiClient.PUT('/api/admin/products/{id}', { params: { path: { id } }, body }));
}

export function setStock(id: number, body: StockInput): Promise<AdminProduct> {
  return send(apiClient.PUT('/api/admin/products/{id}/stock', { params: { path: { id } }, body }));
}

/** Shows the saved product at once, then refreshes every list or page that could show it. */
export function refreshAfterSave(queryClient: QueryClient, product: AdminProduct): void {
  queryClient.setQueryData(adminProductQuery(product.id).queryKey, product);
  void queryClient.invalidateQueries({ queryKey: ['admin'] });
  void queryClient.invalidateQueries({ queryKey: ['products'] });
  void queryClient.invalidateQueries({ queryKey: ['product', product.slug] });
}
