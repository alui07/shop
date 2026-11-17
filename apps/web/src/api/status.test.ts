import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/fetchStub';
import { fetchStatus } from './status';

afterEach(() => {
  vi.unstubAllGlobals();
});

function stubStatus(response: Response) {
  return stubFetch({ '/api/status': () => response });
}

describe('fetchStatus', () => {
  it('returns the status payload', async () => {
    stubStatus(jsonResponse({ status: 'ok', version: '1.2.3' }));

    await expect(fetchStatus()).resolves.toEqual({ status: 'ok', version: '1.2.3' });
  });

  it('passes the abort signal to fetch', async () => {
    const fetchMock = stubStatus(jsonResponse({ status: 'ok', version: '1.2.3' }));
    const controller = new AbortController();

    await fetchStatus(controller.signal);
    controller.abort();

    expect(fetchMock.mock.calls[0]?.[0].signal.aborted).toBe(true);
  });

  it('rejects on a non-2xx response', async () => {
    stubStatus(jsonResponse({}, 503));

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
    stubStatus(jsonResponse(body));

    await expect(fetchStatus()).rejects.toThrow('unexpected payload');
  });
});
