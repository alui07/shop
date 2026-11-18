import { queryOptions } from '@tanstack/react-query';
import type { CatalogSearch } from '@/lib/catalogSearch';
import { apiClient } from './client';

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`API request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
  }
}

export function unwrap<T>(result: { data?: T; response: Response }): T {
  if (result.data === undefined) throw new ApiError(result.response.status);
  return result.data;
}

export const categoriesQuery = queryOptions({
  queryKey: ['categories'],
  queryFn: async ({ signal }) => unwrap(await apiClient.GET('/api/categories', { signal })),
});

export function productsQuery(search: CatalogSearch) {
  return queryOptions({
    queryKey: ['products', search],
    queryFn: async ({ signal }) =>
      unwrap(
        await apiClient.GET('/api/products', {
          params: {
            query: {
              q: search.q,
              category: search.category,
              sort: search.sort,
              page: search.page === undefined ? undefined : search.page - 1,
            },
          },
          signal,
        }),
      ),
  });
}

export function productQuery(slug: string) {
  return queryOptions({
    queryKey: ['product', slug],
    queryFn: async ({ signal }) =>
      unwrap(await apiClient.GET('/api/products/{slug}', { params: { path: { slug } }, signal })),
  });
}
