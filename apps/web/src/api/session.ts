import { queryOptions } from '@tanstack/react-query';
import { apiClient } from './client';
import { unwrap } from './catalog';

const ME_STALE_TIME_MS = 30_000;

export const meQuery = queryOptions({
  queryKey: ['me'],
  staleTime: ME_STALE_TIME_MS,
  refetchOnWindowFocus: true,
  queryFn: async ({ signal }) => unwrap(await apiClient.GET('/api/me', { signal })),
});
