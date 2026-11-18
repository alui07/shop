import { afterEach, describe, expect, it, vi } from 'vitest';
import { csrfHeaders, readCsrfToken } from './csrf';

function setCookies(value: string) {
  vi.spyOn(document, 'cookie', 'get').mockReturnValue(value);
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('readCsrfToken', () => {
  it('returns the decoded token among other cookies', () => {
    setCookies('theme=dark; __Host-XSRF-TOKEN=a%2Bb-1; other=x');

    expect(readCsrfToken()).toBe('a+b-1');
  });

  it('returns undefined when the cookie is absent', () => {
    setCookies('theme=dark; XSRF-TOKEN=nope');

    expect(readCsrfToken()).toBeUndefined();
  });

  it('reads the cookie fresh on every call', () => {
    const spy = vi.spyOn(document, 'cookie', 'get');
    spy.mockReturnValue('__Host-XSRF-TOKEN=first');
    expect(readCsrfToken()).toBe('first');

    spy.mockReturnValue('__Host-XSRF-TOKEN=second');
    expect(readCsrfToken()).toBe('second');
  });
});

describe('csrfHeaders', () => {
  it('carries the token in X-XSRF-TOKEN', () => {
    setCookies('__Host-XSRF-TOKEN=abc');

    expect(csrfHeaders()).toEqual({ 'X-XSRF-TOKEN': 'abc' });
  });

  it('is empty without a token', () => {
    setCookies('');

    expect(csrfHeaders()).toEqual({});
  });
});
