import { useEffect, useState } from 'react';
import { fetchStatus, type ApiStatus } from './api/status';

type StatusState =
  { kind: 'loading' } | { kind: 'ok'; status: ApiStatus } | { kind: 'unavailable' };

const DEFAULT_RETRY_INTERVAL_MS = 5000;

function useApiStatus(retryIntervalMs: number): StatusState {
  const [state, setState] = useState<StatusState>({ kind: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    let retryTimer: ReturnType<typeof setTimeout> | undefined;

    const load = async () => {
      try {
        const status = await fetchStatus(controller.signal);
        setState({ kind: 'ok', status });
      } catch {
        if (controller.signal.aborted) return;
        setState({ kind: 'unavailable' });
        retryTimer = setTimeout(() => void load(), retryIntervalMs);
      }
    };
    void load();

    return () => {
      controller.abort();
      clearTimeout(retryTimer);
    };
  }, [retryIntervalMs]);

  return state;
}

export function App({ retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS }: { retryIntervalMs?: number }) {
  const state = useApiStatus(retryIntervalMs);

  return (
    <main>
      <h1>Shop</h1>
      {state.kind === 'loading' && <p>Checking API status...</p>}
      {state.kind === 'unavailable' && <p role="alert">API unavailable</p>}
      {state.kind === 'ok' && (
        <dl>
          <dt>Status</dt>
          <dd>{state.status.status}</dd>
          <dt>Version</dt>
          <dd>{state.status.version}</dd>
        </dl>
      )}
    </main>
  );
}
