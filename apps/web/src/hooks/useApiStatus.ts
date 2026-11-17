import { useEffect, useState } from 'react';
import { fetchStatus, type ApiStatus } from '@/api/status';

export type StatusState =
  { kind: 'loading' } | { kind: 'ok'; status: ApiStatus } | { kind: 'unavailable' };

export function useApiStatus(retryIntervalMs: number): StatusState {
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
