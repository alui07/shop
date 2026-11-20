import { afterEach, describe, expect, it, vi } from 'vitest';
import { dropIdempotencyKey, idempotencyKeyFor } from './checkoutKey';

afterEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('idempotencyKeyFor', () => {
  it('creates a UUID and keeps it under checkout:<cartVersion>', () => {
    const key = idempotencyKeyFor(3);

    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect(sessionStorage.getItem('checkout:3')).toBe(key);
  });

  it('reuses the stored key for the same cart version only', () => {
    const key = idempotencyKeyFor(3);

    expect(idempotencyKeyFor(3)).toBe(key);
    expect(idempotencyKeyFor(4)).not.toBe(key);
  });

  it('still returns a key when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    expect(idempotencyKeyFor(3)).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('dropIdempotencyKey', () => {
  it('removes the stored key', () => {
    idempotencyKeyFor(3);
    dropIdempotencyKey(3);

    expect(sessionStorage.getItem('checkout:3')).toBeNull();
  });

  it('ignores unavailable storage', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    expect(() => dropIdempotencyKey(3)).not.toThrow();
  });
});
