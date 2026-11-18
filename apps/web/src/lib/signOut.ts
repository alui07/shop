import { csrfHeaders } from '@/lib/csrf';

function isWebUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  try {
    const { protocol } = new URL(value);
    return protocol === 'https:' || protocol === 'http:';
  } catch {
    return false;
  }
}

/**
 * Ends the app session, then leaves for the identity provider's logout page. `/logout` is not in the OpenAPI
 * document, so the generated client's CSRF middleware does not cover it. Returns false when sign-out failed.
 */
export async function signOut(): Promise<boolean> {
  try {
    const response = await fetch('/logout', {
      method: 'POST',
      headers: { Accept: 'application/json', ...csrfHeaders() },
      credentials: 'same-origin',
    });
    if (!response.ok) return false;
    const body = (await response.json()) as { redirectUrl?: unknown };
    if (!isWebUrl(body.redirectUrl)) return false;
    window.location.assign(body.redirectUrl);
    return true;
  } catch {
    return false;
  }
}
