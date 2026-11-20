import { z } from '@/lib/zod';

export const MAX_NAME_LENGTH = 120;
export const MAX_DESCRIPTION_LENGTH = 2000;
export const MAX_SLUG_LENGTH = 100;
export const MAX_PRICE_MINOR = 10_000_000;
export const MAX_ON_HAND = 1_000_000;

const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const NAME_PATTERN = /^[^\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}]*$/u;
const DESCRIPTION_PATTERN = /^(?:\n|[^\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}])*$/u;
const PRICE_PATTERN = /^\d{1,6}(\.\d{1,2})?$/;
const WHOLE_NUMBER_PATTERN = /^\d{1,7}$/;
const LAST_BLANK_CODE_UNIT = 0x20;

/** Matches the server's rule: at least one character above U+0020 (U+00A0 counts, as it does for Hibernate). */
function hasVisibleCharacter(value: string): boolean {
  return [...value].some((character) => character.charCodeAt(0) > LAST_BLANK_CODE_UNIT);
}

/** Converts a validated euro string such as "12.3" to minor units without floating point. */
export function priceToMinor(price: string): number {
  const [euros, cents = ''] = price.split('.');
  return Number(euros) * 100 + Number(cents.padEnd(2, '0'));
}

export function minorToPrice(minor: number): string {
  return `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`;
}

export const productFormSchema = z.object({
  slug: z
    .string()
    .min(1, 'Enter a slug')
    .max(MAX_SLUG_LENGTH, `Use at most ${MAX_SLUG_LENGTH} characters`)
    .regex(SLUG_PATTERN, 'Use lowercase letters and digits separated by single hyphens'),
  name: z
    .string()
    .max(MAX_NAME_LENGTH, `Use at most ${MAX_NAME_LENGTH} characters`)
    .refine(hasVisibleCharacter, 'Enter a name')
    .regex(NAME_PATTERN, 'Use plain text without control or invisible characters'),
  description: z
    .string()
    .max(MAX_DESCRIPTION_LENGTH, `Use at most ${MAX_DESCRIPTION_LENGTH} characters`)
    .refine(hasVisibleCharacter, 'Enter a description')
    .regex(
      DESCRIPTION_PATTERN,
      'Use plain text; only line breaks are allowed as control characters',
    ),
  price: z
    .string()
    .regex(PRICE_PATTERN, 'Enter a price such as 12.34')
    .refine(
      (price) => priceToMinor(price) <= MAX_PRICE_MINOR,
      'The price can be at most 100000.00',
    ),
  categorySlug: z.string().min(1, 'Choose a category').max(MAX_SLUG_LENGTH),
  active: z.boolean(),
  version: z.number().int().min(0),
});

export const stockFormSchema = z.object({
  onHand: z
    .string()
    .regex(WHOLE_NUMBER_PATTERN, 'Enter a whole number')
    .refine((onHand) => Number(onHand) <= MAX_ON_HAND, `The stock can be at most ${MAX_ON_HAND}`),
  version: z.number().int().min(0),
});

export type ProductFormValues = z.input<typeof productFormSchema>;
export type StockFormValues = z.input<typeof stockFormSchema>;

const fieldErrorSchema = z.object({ field: z.string(), message: z.string() });

export const problemSchema = z.object({
  type: z.string().optional(),
  detail: z.string().optional(),
  errors: z.array(fieldErrorSchema).default([]),
});

export type Problem = z.output<typeof problemSchema>;
export type FieldProblem = z.output<typeof fieldErrorSchema>;

export const VALIDATION_TYPE = 'tag:shop.dev,2026:validation';
export const DUPLICATE_SLUG_TYPE = 'tag:shop.dev,2026:duplicate-slug';
export const VERSION_CONFLICT_TYPE = 'tag:shop.dev,2026:version-conflict';
