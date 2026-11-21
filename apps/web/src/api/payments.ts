import { queryOptions } from '@tanstack/react-query';
import { unwrap } from './catalog';
import { apiClient } from './client';
import { sendRequest } from './problemError';

export function startPayment(orderId: string) {
  return sendRequest(
    apiClient.POST('/api/orders/{id}/payment', { params: { path: { id: orderId } } }),
  );
}

export function fakePspPaymentQuery(token: string) {
  return queryOptions({
    queryKey: ['fake-psp-payment', token],
    queryFn: async ({ signal }) =>
      unwrap(
        await apiClient.GET('/api/fake-psp/payments/{token}', {
          params: { path: { token } },
          signal,
        }),
      ),
  });
}

export function completeFakePayment(token: string, outcome: 'SUCCEEDED' | 'DECLINED') {
  return sendRequest(
    apiClient.POST('/api/fake-psp/payments/{token}/complete', {
      params: { path: { token } },
      body: { outcome },
    }),
  );
}
