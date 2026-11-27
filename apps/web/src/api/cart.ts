import { queryOptions } from '@tanstack/react-query';
import { unwrap } from './catalog';
import { apiClient } from './client';
import { sendRequest } from './problemError';

export const cartQuery = queryOptions({
  queryKey: ['cart'],
  queryFn: async ({ signal }) => unwrap(await apiClient.GET('/api/cart', { signal })),
});

export function putCartLine(slug: string, quantity: number) {
  return sendRequest(
    apiClient.PUT('/api/cart/lines/{slug}', {
      params: { path: { slug } },
      body: { quantity },
    }),
  );
}

export function addToCartLine(slug: string, quantity: number, idempotencyKey: string) {
  return sendRequest(
    apiClient.POST('/api/cart/lines/{slug}/additions', {
      params: { path: { slug }, header: { 'Idempotency-Key': idempotencyKey } },
      body: { quantity },
    }),
  );
}

export function removeCartLine(slug: string) {
  return sendRequest(apiClient.DELETE('/api/cart/lines/{slug}', { params: { path: { slug } } }));
}
