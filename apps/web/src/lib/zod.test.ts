import { describe, expect, it } from 'vitest';
import { z } from './zod';

describe('zod configuration', () => {
  it('runs without eval-based code generation', () => {
    expect(z.config().jitless).toBe(true);
  });
});
