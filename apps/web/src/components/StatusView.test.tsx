import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/fetchStub';
import { StatusView } from './StatusView';

const healthy = () => jsonResponse({ status: 'ok', version: '1.2.3' });

function abortAwareFetch() {
  const fetchMock = vi.fn(
    (request: Request) =>
      new Promise<Response>((_resolve, reject) => {
        request.signal.addEventListener('abort', () =>
          reject(new DOMException('aborted', 'AbortError')),
        );
      }),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('StatusView', () => {
  it('shows status and version when the API is reachable', async () => {
    const fetchMock = stubFetch({ '/api/status': healthy });

    render(<StatusView />);

    expect((await screen.findByText('ok')).tagName).toBe('DD');
    expect(screen.getByText('1.2.3').tagName).toBe('DD');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('API status');
    expect(document.title).toBe('API status | Shop');
    expect(fetchMock.mock.calls[0]?.[0].url).toBe(`${window.location.origin}/api/status`);
  });

  it('shows API unavailable and recovers on retry', async () => {
    let calls = 0;
    stubFetch({
      '/api/status': () => {
        calls += 1;
        if (calls === 1) throw new TypeError('network down');
        return healthy();
      },
    });

    render(<StatusView retryIntervalMs={10} />);

    expect((await screen.findByRole('alert')).textContent).toBe('API unavailable');
    expect((await screen.findByText('1.2.3')).tagName).toBe('DD');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('treats an error response as unavailable', async () => {
    stubFetch({ '/api/status': () => jsonResponse({}, 502) });

    render(<StatusView />);

    expect((await screen.findByRole('alert')).textContent).toBe('API unavailable');
  });

  it('shows the loading state before the request settles', () => {
    abortAwareFetch();

    render(<StatusView />);

    expect(screen.getByText('Checking API status...').tagName).toBe('P');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('aborts a pending request on unmount without showing an error', async () => {
    const fetchMock = abortAwareFetch();
    const { unmount } = render(<StatusView retryIntervalMs={10} />);
    const signal = fetchMock.mock.calls[0]?.[0].signal;

    unmount();
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(signal?.aborted).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('cancels the pending retry on unmount', async () => {
    vi.useFakeTimers();
    const fetchMock = stubFetch({
      '/api/status': () => {
        throw new TypeError('network down');
      },
    });
    const { unmount } = render(<StatusView retryIntervalMs={1000} />);
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.getByRole('alert').textContent).toBe('API unavailable');
    expect(fetchMock).toHaveBeenCalledTimes(1);

    unmount();
    await vi.advanceTimersByTimeAsync(5000);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
