import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { jsonResponse, stubFetch } from '@/test/fetchStub';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('apiClient', () => {
  it('sends same-origin requests through the current global fetch', async () => {
    const fetchMock = stubFetch({
      '/api/status': () => jsonResponse({ status: 'ok', version: '1' }),
    });

    const { data } = await apiClient.GET('/api/status', {});

    expect(data).toEqual({ status: 'ok', version: '1' });
    expect(fetchMock.mock.calls[0]?.[0].url).toBe(`${window.location.origin}/api/status`);
  });

  it('forwards an abort to the request signal under jsdom', async () => {
    const controller = new AbortController();
    const fetchMock = stubFetch({
      '/api/status': () => jsonResponse({ status: 'ok', version: '1' }),
    });

    await apiClient.GET('/api/status', { signal: controller.signal });
    controller.abort();

    const requestSignal = fetchMock.mock.calls[0]?.[0].signal;
    expect(requestSignal?.aborted).toBe(true);
  });
});
