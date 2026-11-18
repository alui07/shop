import { queryOptions } from '@tanstack/react-query';
import type { AdminSearch } from '@/lib/adminSearch';
import { unwrap } from './catalog';
import { apiClient } from './client';

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
