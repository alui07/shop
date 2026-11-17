import { describe, expect, it } from 'vitest';
import { createQueryClient } from './queryClient';

describe('createQueryClient', () => {
  it('does not retry failed queries and keeps data fresh for a while', () => {
    const options = createQueryClient().getDefaultOptions().queries;

    expect(options?.retry).toBe(false);
    expect(options?.staleTime).toBeGreaterThan(0);
  });
});
