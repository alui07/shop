import type { QueryClient } from '@tanstack/react-query';
import type { Middleware } from 'openapi-fetch';
import { CSRF_HEADER_NAME, readCsrfToken } from '@/lib/csrf';
import { meQuery } from './session';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);
const ME_PATH = '/api/me';

export function createApiMiddleware(queryClient: QueryClient): Middleware {
  return {
    onRequest({ request }) {
      const token = readCsrfToken();
      if (!SAFE_METHODS.has(request.method) && token !== undefined) {
        request.headers.set(CSRF_HEADER_NAME, token);
      }
      return request;
    },
    onResponse({ request, response }) {
      // A 401 from /api/me would refetch /api/me forever; any other 401 means the session ended.
      if (response.status === 401 && new URL(request.url).pathname !== ME_PATH) {
        void queryClient.invalidateQueries({ queryKey: meQuery.queryKey });
      }
      return response;
    },
  };
}
