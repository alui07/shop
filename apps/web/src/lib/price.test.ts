import { afterEach, describe, expect, it, vi } from 'vitest';
import { formatPrice } from './price';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('formatPrice', () => {
  it('formats minor units of a two-decimal currency', () => {
    expect(formatPrice({ amountMinor: 12999, currency: 'EUR' })).toBe('€129.99');
  });

  it('keeps trailing zeros', () => {
    expect(formatPrice({ amountMinor: 500, currency: 'USD' })).toBe('$5.00');
  });

  it('respects currencies without minor units', () => {
    expect(formatPrice({ amountMinor: 500, currency: 'JPY' })).toBe('¥500');
  });

  it('assumes two minor-unit digits when the runtime does not report them', () => {
    vi.spyOn(Intl.NumberFormat.prototype, 'resolvedOptions').mockReturnValue(
      {} as Intl.ResolvedNumberFormatOptions,
    );

    expect(formatPrice({ amountMinor: 500, currency: 'USD' })).toBe('$5.00');
  });
});
