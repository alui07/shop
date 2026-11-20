const PRODUCT_ID_PATTERN = /^[1-9]\d{0,14}$/;

/** A positive integer that fits safely in a JavaScript number, or undefined. */
export function parseProductId(raw: string): number | undefined {
  return PRODUCT_ID_PATTERN.test(raw) ? Number(raw) : undefined;
}
