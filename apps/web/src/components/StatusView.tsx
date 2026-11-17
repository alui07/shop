import { useApiStatus } from '@/hooks/useApiStatus';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';

const DEFAULT_RETRY_INTERVAL_MS = 5000;

export function StatusView({
  retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS,
}: {
  retryIntervalMs?: number;
}) {
  useDocumentTitle('API status');
  const state = useApiStatus(retryIntervalMs);

  return (
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">API status</h1>
      {state.kind === 'loading' && <p>Checking API status...</p>}
      {state.kind === 'unavailable' && <p role="alert">API unavailable</p>}
      {state.kind === 'ok' && (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1">
          <dt className="font-medium">Status</dt>
          <dd>{state.status.status}</dd>
          <dt className="font-medium">Version</dt>
          <dd>{state.status.version}</dd>
        </dl>
      )}
    </section>
  );
}
