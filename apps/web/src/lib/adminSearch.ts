import { pageField, queryField } from '@/lib/catalogSearch';
import { z } from '@/lib/zod';

const adminSearchSchema = z.object({ q: queryField, page: pageField });

export type AdminSearch = z.output<typeof adminSearchSchema>;

export function parseAdminSearch(raw: Record<string, unknown>): AdminSearch {
  return adminSearchSchema.parse(raw);
}
