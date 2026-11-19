import type { components } from '@/api/schema';
import {
  DUPLICATE_SLUG_TYPE,
  VALIDATION_TYPE,
  VERSION_CONFLICT_TYPE,
  minorToPrice,
  priceToMinor,
  type Problem,
  type ProductFormValues,
  type StockFormValues,
} from '@/lib/adminSchemas';

type AdminProduct = components['schemas']['AdminProduct'];
type ProductInput = components['schemas']['ProductInput'];
type ProductUpdateInput = components['schemas']['ProductUpdateInput'];
type StockInput = components['schemas']['StockInput'];

export const CONFLICT_MESSAGE = 'This product was changed by someone else.';
const CHECK_FORM_MESSAGE = 'Check the form and try again.';
const DEFAULT_FAILURE_MESSAGE = 'The change could not be saved. Try again.';
const FAILURE_MESSAGES: Record<number, string> = {
  0: 'The request could not be sent. Check your connection and try again.',
  401: 'Your session has ended. Sign in again.',
  403: 'You do not have permission to do this.',
  503: 'The shop is temporarily unavailable. Try again in a moment.',
};

export const EMPTY_PRODUCT_VALUES: ProductFormValues = {
  slug: '',
  name: '',
  description: '',
  price: '',
  categorySlug: '',
  active: false,
  version: 0,
};

/** Server field names to the form's field names. */
export const PRODUCT_SERVER_FIELDS: Record<string, string> = {
  slug: 'slug',
  name: 'name',
  description: 'description',
  priceMinor: 'price',
  categorySlug: 'categorySlug',
};
export const STOCK_SERVER_FIELDS: Record<string, string> = { onHand: 'onHand' };

export function toProductFormValues(product: AdminProduct): ProductFormValues {
  return {
    slug: product.slug,
    name: product.name,
    description: product.description,
    price: minorToPrice(product.price.amountMinor),
    categorySlug: product.category.slug,
    active: product.active,
    version: product.version,
  };
}

export function toStockFormValues(product: AdminProduct): StockFormValues {
  return { onHand: String(product.stock.onHand), version: product.stock.version };
}

export function toProductInput(values: ProductFormValues): ProductInput {
  return {
    slug: values.slug,
    name: values.name,
    description: values.description,
    priceMinor: priceToMinor(values.price),
    categorySlug: values.categorySlug,
    active: values.active,
  };
}

export function toProductUpdateInput(values: ProductFormValues): ProductUpdateInput {
  return {
    name: values.name,
    description: values.description,
    priceMinor: priceToMinor(values.price),
    categorySlug: values.categorySlug,
    active: values.active,
    version: values.version,
  };
}

export function toStockInput(values: StockFormValues): StockInput {
  return { onHand: Number(values.onHand), version: values.version };
}

export interface SubmitErrors {
  form?: string;
  fields: Record<string, string>;
}

/** Places server messages under the fields the form shows; anything else goes to the form-level alert. */
export function toSubmitErrors(
  failure: { status: number; problem: Problem },
  serverFields: Record<string, string>,
): SubmitErrors {
  const { status, problem } = failure;
  if (problem.type === VERSION_CONFLICT_TYPE) return { form: CONFLICT_MESSAGE, fields: {} };
  if (problem.type !== VALIDATION_TYPE && problem.type !== DUPLICATE_SLUG_TYPE) {
    // The API's 503 details are fixed server texts, so they are safe to show.
    const serverText = status === 503 ? problem.detail : undefined;
    return { form: serverText ?? FAILURE_MESSAGES[status] ?? DEFAULT_FAILURE_MESSAGE, fields: {} };
  }
  const fields: Record<string, string> = {};
  const unplaced: string[] = [];
  for (const { field, message } of problem.errors) {
    const formField = serverFields[field];
    if (formField === undefined) unplaced.push(message);
    else fields[formField] = message;
  }
  const hasFieldErrors = Object.keys(fields).length > 0;
  if (unplaced.length > 0) return { form: unplaced.join(' '), fields };
  return hasFieldErrors ? { fields } : { form: CHECK_FORM_MESSAGE, fields };
}
