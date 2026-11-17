import { describe, expect, it } from 'vitest';
import { parseSearch, stringifySearch } from './searchParams';

describe('parseSearch', () => {
  it('reads every value as a string', () => {
    expect(parseSearch('?q=123&page=2&category=a%20b')).toEqual({
      q: '123',
      page: '2',
      category: 'a b',
    });
  });

  it('returns an empty object for an empty search', () => {
    expect(parseSearch('')).toEqual({});
  });
});

describe('stringifySearch', () => {
  it('writes plain values and skips undefined ones', () => {
    expect(stringifySearch({ q: '123', page: 2, sort: undefined })).toBe('?q=123&page=2');
  });

  it('returns an empty string when nothing is left', () => {
    expect(stringifySearch({ q: undefined })).toBe('');
  });
});
