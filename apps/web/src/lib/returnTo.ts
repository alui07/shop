const STORAGE_KEY = 'shop.returnTo';
const MAX_RETURN_TO_LENGTH = 2000;

export function saveReturnTo(): void {
  try {
    const { pathname, search, hash } = window.location;
    window.sessionStorage.setItem(STORAGE_KEY, `${pathname}${search}${hash}`);
  } catch {
    // Storage can be blocked; signing in then simply lands on the home page.
  }
}

function toSameOriginPath(value: string): string | null {
  if (value.length > MAX_RETURN_TO_LENGTH) return null;
  const url = new URL(value, window.location.origin);
  if (url.origin !== window.location.origin) return null;
  return `${url.pathname}${url.search}${url.hash}`;
}

/** Removes the saved value and returns it only when the sign-in succeeded and it is a same-origin path. */
export function takeReturnTo(signedIn: boolean): string | null {
  try {
    const saved = window.sessionStorage.getItem(STORAGE_KEY);
    if (saved === null) return null;
    window.sessionStorage.removeItem(STORAGE_KEY);
    return signedIn ? toSameOriginPath(saved) : null;
  } catch {
    return null;
  }
}
