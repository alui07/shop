import { z } from '@/lib/zod';

const fieldErrorSchema = z.object({ field: z.string(), message: z.string() });

export const problemSchema = z.object({
  type: z.string().optional(),
  detail: z.string().optional(),
  errors: z.array(fieldErrorSchema).default([]),
  quantityInCart: z.number().int().optional(),
  maxQuantity: z.number().int().optional(),
});

export type Problem = z.output<typeof problemSchema>;
export type FieldProblem = z.output<typeof fieldErrorSchema>;
