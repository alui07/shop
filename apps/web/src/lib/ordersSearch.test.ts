import { describe, expect, it } from 'vitest';
import { parseOrdersSearch } from './ordersSearch';

describe('parseOrdersSearch', () => {
  it('reads the page', () => {
    expect(parseOrdersSearch({ page: 2 })).toEqual({ page: 2 });
  });

  it('allows no page', () => {
    expect(parseOrdersSearch({})).toEqual({});
  });
});
