import { QueryClient } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApiMiddleware } from './middleware';

afterEach(() => {
  vi.restoreAllMocks();
});

function setCookies(value: string) {
  vi.spyOn(document, 'cookie', 'get').mockReturnValue(value);
}

async function sendRequest(method: string) {
  const { onRequest } = createApiMiddleware(new QueryClient());
  const request = new Request('http://localhost/api/admin/products', { method });
  await onRequest?.({ request } as Parameters<NonNullable<typeof onRequest>>[0]);
  return request;
}

describe('CSRF header', () => {
  it('is added to unsafe requests', async () => {
    setCookies('__Host-XSRF-TOKEN=abc');

    expect((await sendRequest('POST')).headers.get('X-XSRF-TOKEN')).toBe('abc');
  });

  it('is not added to safe requests', async () => {
    setCookies('__Host-XSRF-TOKEN=abc');

    expect((await sendRequest('GET')).headers.get('X-XSRF-TOKEN')).toBeNull();
  });

  it('is not added when there is no token', async () => {
    setCookies('');

    expect((await sendRequest('DELETE')).headers.get('X-XSRF-TOKEN')).toBeNull();
  });
});

describe('401 handling', () => {
  async function respond(path: string, status: number) {
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue();
    const { onResponse } = createApiMiddleware(queryClient);
    const request = new Request(`http://localhost${path}`);
    await onResponse?.({
      request,
      response: new Response(null, { status }),
    } as Parameters<NonNullable<typeof onResponse>>[0]);
    return invalidate;
  }

  it('invalidates the session query', async () => {
    const invalidate = await respond('/api/admin/products', 401);

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['me'] });
  });

  it('leaves the session query alone for other statuses', async () => {
    expect(await respond('/api/admin/products', 403)).not.toHaveBeenCalled();
  });

  it('does not refetch the session query after its own 401', async () => {
    expect(await respond('/api/me', 401)).not.toHaveBeenCalled();
  });
});
