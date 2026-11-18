import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { saveReturnTo, takeReturnTo } from './returnTo';

const KEY = 'shop.returnTo';

beforeEach(() => {
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

describe('saveReturnTo', () => {
  it('stores path, search and hash', () => {
    window.history.replaceState(null, '', '/admin?q=oak#top');

    saveReturnTo();

    expect(window.sessionStorage.getItem(KEY)).toBe('/admin?q=oak#top');
  });

  it('tolerates blocked storage', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    expect(() => saveReturnTo()).not.toThrow();
  });
});

describe('takeReturnTo', () => {
  function stored(value: string) {
    window.sessionStorage.setItem(KEY, value);
  }

  it('returns nothing when nothing was saved', () => {
    expect(takeReturnTo(true)).toBeNull();
  });

  it('returns the saved path once and removes it', () => {
    stored('/admin?q=oak#top');

    expect(takeReturnTo(true)).toBe('/admin?q=oak#top');
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
  });

  it('accepts an absolute URL on the same origin and keeps only its path', () => {
    stored(`${window.location.origin}/status?a=1`);

    expect(takeReturnTo(true)).toBe('/status?a=1');
  });

  it('discards the value when the visitor is not signed in', () => {
    stored('/admin');

    expect(takeReturnTo(false)).toBeNull();
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
  });

  it.each([
    ['another origin', 'https://evil.example/admin'],
    ['a protocol-relative URL', '//evil.example/admin'],
    ['a backslash URL', '/\\evil.example/admin'],
    ['a malformed URL', 'http://['],
    ['an overlong value', `/${'a'.repeat(2000)}`],
  ])('rejects %s', (_name, value) => {
    stored(value);

    expect(takeReturnTo(true)).toBeNull();
  });

  it('tolerates blocked storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    expect(takeReturnTo(true)).toBeNull();
  });
});
