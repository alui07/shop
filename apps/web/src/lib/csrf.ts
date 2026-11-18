export const CSRF_COOKIE_NAME = '__Host-XSRF-TOKEN';
export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN';

/** Reads the token from the cookie on every call: login and logout replace it, so it must never be cached. */
export function readCsrfToken(): string | undefined {
  const prefix = `${CSRF_COOKIE_NAME}=`;
  const entry = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));
  return entry === undefined ? undefined : decodeURIComponent(entry.slice(prefix.length));
}

export function csrfHeaders(): Record<string, string> {
  const token = readCsrfToken();
  return token === undefined ? {} : { [CSRF_HEADER_NAME]: token };
}
