import { describe, expect, it } from 'vitest';
import { parseAdminSearch } from './adminSearch';

describe('parseAdminSearch', () => {
  it('keeps a trimmed query and a page from 2 up', () => {
    expect(parseAdminSearch({ q: '  oak ', page: '3' })).toEqual({ q: 'oak', page: 3 });
  });

  it('drops invalid values and unknown keys', () => {
    expect(parseAdminSearch({ q: 'a\u0001b', page: '1', sort: 'name', login: 'failed' })).toEqual(
      {},
    );
  });
});
