import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function abortAwareFetch() {
  return vi.fn().mockImplementation(
    (_url: string, init: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init.signal?.addEventListener('abort', () =>
          reject(new DOMException('aborted', 'AbortError')),
        );
      }),
  );
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('App', () => {
  it('shows status and version when the API is reachable', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ status: 'ok', version: '1.2.3' }));
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);

    expect((await screen.findByText('ok')).tagName).toBe('DD');
    expect(screen.getByText('1.2.3').tagName).toBe('DD');
    expect(fetchMock).toHaveBeenCalledWith('/api/status', expect.anything());
  });

  it('shows API unavailable and recovers on retry', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValue(jsonResponse({ status: 'ok', version: '1.2.3' }));
    vi.stubGlobal('fetch', fetchMock);

    render(<App retryIntervalMs={10} />);

    expect((await screen.findByRole('alert')).textContent).toBe('API unavailable');
    expect((await screen.findByText('1.2.3')).tagName).toBe('DD');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('treats an error response as unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, 502)));

    render(<App />);

    expect((await screen.findByRole('alert')).textContent).toBe('API unavailable');
  });

  it('shows the loading state before the request settles', () => {
    vi.stubGlobal('fetch', abortAwareFetch());

    render(<App />);

    expect(screen.getByText('Checking API status...').tagName).toBe('P');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('aborts a pending request on unmount without showing an error', async () => {
    const fetchMock = abortAwareFetch();
    vi.stubGlobal('fetch', fetchMock);
    const { unmount } = render(<App retryIntervalMs={10} />);
    const signal = (fetchMock.mock.calls[0]?.[1] as RequestInit).signal;

    unmount();
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(signal?.aborted).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('cancels the pending retry on unmount', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('network down'));
    vi.stubGlobal('fetch', fetchMock);
    const { unmount } = render(<App retryIntervalMs={1000} />);
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.getByRole('alert').textContent).toBe('API unavailable');
    expect(fetchMock).toHaveBeenCalledTimes(1);

    unmount();
    await vi.advanceTimersByTimeAsync(5000);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
