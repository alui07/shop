import { describe, expect, it } from 'vitest';
import { parseCatalogSearch } from './catalogSearch';

describe('parseCatalogSearch', () => {
  it('keeps valid values', () => {
    expect(
      parseCatalogSearch({ q: 'oak chair', category: 'chairs', sort: 'price_asc', page: '3' }),
    ).toEqual({ q: 'oak chair', category: 'chairs', sort: 'price_asc', page: 3 });
  });

  it('accepts numeric page values and trims text', () => {
    expect(parseCatalogSearch({ q: '  lamp  ', page: 2 })).toEqual({ q: 'lamp', page: 2 });
  });

  it('returns no values for an empty search', () => {
    expect(parseCatalogSearch({})).toEqual({});
  });

  it.each([
    ['a blank query', { q: '   ' }],
    ['an over-long query', { q: 'x'.repeat(101) }],
    ['a blank category', { category: ' ' }],
    ['a query with a control character', { q: 'a\u0001b' }],
    ['a query with a tab', { q: 'brass\tlamp' }],
    ['a category with a control character', { category: 'bath\u007f' }],
    ['an over-long category', { category: 'c'.repeat(101) }],
    ['an unknown sort', { sort: 'cheapest' }],
    ['a non-numeric page', { page: 'two' }],
    ['page 1, which is the default', { page: '1' }],
    ['page 0', { page: '0' }],
    ['a negative page', { page: '-4' }],
    ['a fractional page', { page: '2.5' }],
    ['a page above the API maximum', { page: '10002' }],
    ['a non-string query', { q: { nested: true } }],
  ])('falls back to the default for %s', (_description, raw) => {
    expect(parseCatalogSearch(raw)).toEqual({});
  });

  it('accepts the highest page the API allows', () => {
    expect(parseCatalogSearch({ page: '10001' })).toEqual({ page: 10001 });
  });
});
