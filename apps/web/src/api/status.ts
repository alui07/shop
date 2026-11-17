import { apiClient } from './client';

export interface ApiStatus {
  status: string;
  version: string;
}

export async function fetchStatus(signal?: AbortSignal): Promise<ApiStatus> {
  const { data, response } = await apiClient.GET('/api/status', { signal });
  if (!response.ok) {
    throw new Error(`GET /api/status failed with ${response.status}`);
  }
  if (!isApiStatus(data)) {
    throw new Error('GET /api/status returned an unexpected payload');
  }
  return data;
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
