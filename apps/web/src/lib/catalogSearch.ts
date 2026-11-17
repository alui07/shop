import { z } from 'zod';

export const SORT_OPTIONS = [
  { value: 'relevance', label: 'Relevance' },
  { value: 'name', label: 'Name' },
  { value: 'price_asc', label: 'Price: low to high' },
  { value: 'price_desc', label: 'Price: high to low' },
] as const;

export type SortValue = (typeof SORT_OPTIONS)[number]['value'];

const SORT_VALUES = ['relevance', 'name', 'price_asc', 'price_desc'] as const satisfies SortValue[];
const MAX_QUERY_LENGTH = 100;
const MAX_URL_PAGE = 10001;
const MAX_CATEGORY_LENGTH = 100;
const DELETE_CHARACTER = 0x7f;

function hasNoControlCharacters(value: string): boolean {
  return [...value].every((character) => {
    const code = character.charCodeAt(0);
    return code >= 0x20 && code !== DELETE_CHARACTER;
  });
}

// Zod probes for eval support with `new Function`, which the enforced CSP reports as a script-src violation.
z.config({ jitless: true });

const catalogSearchSchema = z.object({
  q: z
    .string()
    .trim()
    .min(1)
    .max(MAX_QUERY_LENGTH)
    .refine(hasNoControlCharacters)
    .optional()
    .catch(undefined),
  category: z
    .string()
    .trim()
    .min(1)
    .max(MAX_CATEGORY_LENGTH)
    .refine(hasNoControlCharacters)
    .optional()
    .catch(undefined),
  sort: z.enum(SORT_VALUES).optional().catch(undefined),
  page: z.coerce.number().int().min(2).max(MAX_URL_PAGE).optional().catch(undefined),
});

export type CatalogSearch = z.output<typeof catalogSearchSchema>;

export function parseCatalogSearch(raw: Record<string, unknown>): CatalogSearch {
  return catalogSearchSchema.parse(raw);
}
