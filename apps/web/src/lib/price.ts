const DEFAULT_MINOR_UNIT_DIGITS = 2;

export interface Money {
  amountMinor: number;
  currency: string;
}

export function formatPrice({ amountMinor, currency }: Money): string {
  const format = new Intl.NumberFormat('en', { style: 'currency', currency });
  const minorUnitDigits =
    format.resolvedOptions().maximumFractionDigits ?? DEFAULT_MINOR_UNIT_DIGITS;
  return format.format(amountMinor / 10 ** minorUnitDigits);
}
