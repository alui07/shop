import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchStatus } from './status';

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('fetchStatus', () => {
  it('returns the status payload', async () => {
    stubFetch(jsonResponse({ status: 'ok', version: '1.2.3' }));

    await expect(fetchStatus()).resolves.toEqual({ status: 'ok', version: '1.2.3' });
  });

  it('passes the abort signal to fetch', async () => {
    const fetchMock = stubFetch(jsonResponse({ status: 'ok', version: '1.2.3' }));
    const controller = new AbortController();

    await fetchStatus(controller.signal);

    expect(fetchMock).toHaveBeenCalledWith('/api/status', { signal: controller.signal });
  });

  it('defaults the signal to null', async () => {
    const fetchMock = stubFetch(jsonResponse({ status: 'ok', version: '1.2.3' }));

    await fetchStatus();

    expect(fetchMock).toHaveBeenCalledWith('/api/status', { signal: null });
  });

  it('rejects on a non-2xx response', async () => {
    stubFetch(jsonResponse({}, 503));

    await expect(fetchStatus()).rejects.toThrow('GET /api/status failed with 503');
  });

  it.each([
    ['a string', 'ok'],
    ['null', null],
    ['an object without status', { version: '1.2.3' }],
    ['an object with a non-string status', { status: 1, version: '1.2.3' }],
    ['an object without version', { status: 'ok' }],
    ['an object with a non-string version', { status: 'ok', version: 2 }],
  ])('rejects a payload that is %s', async (_description, body) => {
    stubFetch(jsonResponse(body));

    await expect(fetchStatus()).rejects.toThrow('unexpected payload');
  });
});
