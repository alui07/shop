const KEY_PREFIX = 'checkout:';

function storageKey(cartVersion: number): string {
  return `${KEY_PREFIX}${cartVersion}`;
}

/** The key of this checkout attempt; stays the same across retries until it is dropped. */
export function idempotencyKeyFor(cartVersion: number): string {
  try {
    const stored = sessionStorage.getItem(storageKey(cartVersion));
    if (stored !== null) return stored;
    const created = crypto.randomUUID();
    sessionStorage.setItem(storageKey(cartVersion), created);
    return created;
  } catch {
    return crypto.randomUUID();
  }
}

export function dropIdempotencyKey(cartVersion: number): void {
  try {
    sessionStorage.removeItem(storageKey(cartVersion));
  } catch {
    // The key was never stored, so there is nothing to drop.
  }
}
