import { queryOptions } from '@tanstack/react-query';
import { unwrap } from './catalog';
import { apiClient } from './client';
import { sendRequest } from './problemError';

export function ordersQuery(page: number | undefined) {
  return queryOptions({
    queryKey: ['orders', page],
    queryFn: async ({ signal }) =>
      unwrap(
        await apiClient.GET('/api/orders', {
          params: { query: { page: page === undefined ? undefined : page - 1 } },
          signal,
        }),
      ),
  });
}

export function orderQuery(id: string) {
  return queryOptions({
    queryKey: ['order', id],
    queryFn: async ({ signal }) =>
      unwrap(await apiClient.GET('/api/orders/{id}', { params: { path: { id } }, signal })),
  });
}

export function cancelOrder(id: string) {
  return sendRequest(apiClient.POST('/api/orders/{id}/cancel', { params: { path: { id } } }));
}

export function submitCheckout(
  idempotencyKey: string,
  body: { cartVersion: number; expectedTotalMinor: number },
) {
  return sendRequest(
    apiClient.POST('/api/checkout', {
      params: { header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  );
}
