export interface ApiStatus {
  status: string;
  version: string;
}

export async function fetchStatus(signal?: AbortSignal): Promise<ApiStatus> {
  const response = await fetch('/api/status', { signal: signal ?? null });
  if (!response.ok) {
    throw new Error(`GET /api/status failed with ${response.status}`);
  }
  const body: unknown = await response.json();
  if (!isApiStatus(body)) {
    throw new Error('GET /api/status returned an unexpected payload');
  }
  return body;
}

function isApiStatus(value: unknown): value is ApiStatus {
  return (
    typeof value === 'object' &&
    value !== null &&
    'status' in value &&
    typeof value.status === 'string' &&
    'version' in value &&
    typeof value.version === 'string'
  );
}
