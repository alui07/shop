import { describe, expect, it } from 'vitest';
import { problemSchema } from './problem';

describe('problemSchema', () => {
  it('defaults errors to an empty list', () => {
    expect(problemSchema.parse({ type: 'x' })).toEqual({ type: 'x', errors: [] });
  });

  it('reads field errors', () => {
    expect(
      problemSchema.parse({ detail: 'd', errors: [{ field: 'slug', message: 'taken' }] }).errors,
    ).toEqual([{ field: 'slug', message: 'taken' }]);
  });

  it('rejects malformed errors', () => {
    expect(problemSchema.safeParse({ errors: [{ field: 1 }] }).success).toBe(false);
  });
});
