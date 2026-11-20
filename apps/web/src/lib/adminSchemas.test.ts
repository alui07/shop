import { describe, expect, it } from 'vitest';
import { minorToPrice, priceToMinor, productFormSchema, stockFormSchema } from './adminSchemas';

const valid = {
  slug: 'oak-desk',
  name: 'Oak desk',
  description: 'Solid oak.\nTwo drawers.',
  price: '12.34',
  categorySlug: 'furniture',
  active: true,
  version: 0,
};

function problemsOf(overrides: Record<string, unknown>) {
  const result = productFormSchema.safeParse({ ...valid, ...overrides });
  return result.success ? [] : result.error.issues.map((issue) => issue.path.join('.'));
}

describe('productFormSchema', () => {
  it('accepts a valid product', () => {
    expect(productFormSchema.safeParse(valid).success).toBe(true);
  });

  it.each([
    ['', true],
    ['a', false],
    ['a'.repeat(100), false],
    ['a'.repeat(101), true],
    ['Bad Slug', true],
    ['-a', true],
    ['a--b', true],
    ['a-b-1', false],
  ])('checks slug %j', (slug, rejected) => {
    expect(problemsOf({ slug }).includes('slug')).toBe(rejected);
  });

  it.each([
    ['', true],
    ['   ', true],
    ['x'.repeat(120), false],
    ['x'.repeat(121), true],
    ['tab\there', true],
    ['\u0085', true],
    ['a​b', true],
    ['a‮b', true],
    ['\ud800', true],
    ['', true],
    ['͸', true],
    [' ', false],
    ['Crème brûlée 🍮', false],
  ])('checks name %j', (name, rejected) => {
    expect(problemsOf({ name }).includes('name')).toBe(rejected);
  });

  it.each([
    ['', true],
    ['x'.repeat(2000), false],
    ['x'.repeat(2001), true],
    ['one\ntwo', false],
    ['one\ttwo', true],
    ['one\rtwo', true],
    ['a​b', true],
    ['\ud800', true],
    [' ', false],
  ])('checks description %j', (description, rejected) => {
    expect(problemsOf({ description }).includes('description')).toBe(rejected);
  });

  it.each([
    ['0', false],
    ['12', false],
    ['12.3', false],
    ['12.34', false],
    ['100000.00', false],
    ['100000.01', true],
    ['999999', true],
    ['1234567', true],
    ['12.345', true],
    ['12.', true],
    ['.5', true],
    ['-1', true],
    ['1e3', true],
    ['', true],
    ['1,5', true],
  ])('checks price %j', (price, rejected) => {
    expect(problemsOf({ price }).includes('price')).toBe(rejected);
  });

  it('requires a category of at most 100 characters', () => {
    expect(problemsOf({ categorySlug: '' })).toEqual(['categorySlug']);
    expect(problemsOf({ categorySlug: 'c'.repeat(101) })).toEqual(['categorySlug']);
    expect(problemsOf({ categorySlug: 'c'.repeat(100) })).toEqual([]);
  });

  it('requires a non-negative integer version', () => {
    expect(problemsOf({ version: -1 })).toEqual(['version']);
    expect(problemsOf({ version: 1.5 })).toEqual(['version']);
  });
});

describe('stockFormSchema', () => {
  it.each([
    ['0', false],
    ['1000000', false],
    ['1000001', true],
    ['12345678', true],
    ['-1', true],
    ['1.5', true],
    ['abc', true],
    ['', true],
  ])('checks on hand %j', (onHand, rejected) => {
    expect(stockFormSchema.safeParse({ onHand, version: 0 }).success).toBe(!rejected);
  });
});

describe('price conversion', () => {
  it.each([
    ['12.34', 1234],
    ['12.3', 1230],
    ['12', 1200],
    ['0.1', 10],
    ['0.07', 7],
    ['100000.00', 10_000_000],
  ])('turns %s euros into %d minor units without float error', (price, minor) => {
    expect(priceToMinor(price)).toBe(minor);
  });

  it.each([
    [1234, '12.34'],
    [1200, '12.00'],
    [7, '0.07'],
    [0, '0.00'],
    [10_000_000, '100000.00'],
  ])('shows %d minor units as %s', (minor, price) => {
    expect(minorToPrice(minor)).toBe(price);
  });
});
