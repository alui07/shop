import { describe, expect, it } from 'vitest';
import { parseProductId } from './productId';

describe('parseProductId', () => {
  it.each([
    ['1', 1],
    ['42', 42],
    ['999999999999999', 999999999999999],
  ])('accepts %s', (raw, id) => {
    expect(parseProductId(raw)).toBe(id);
  });

  it.each(['0', '-1', '01', '1.5', 'abc', '', '1000000000000000', ' 1'])('rejects %j', (raw) => {
    expect(parseProductId(raw)).toBeUndefined();
  });
});
