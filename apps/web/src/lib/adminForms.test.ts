import { describe, expect, it } from 'vitest';
import { adminProduct } from '@/test/fixtures';
import {
  CONFLICT_MESSAGE,
  PRODUCT_SERVER_FIELDS,
  STOCK_SERVER_FIELDS,
  toProductFormValues,
  toProductInput,
  toProductUpdateInput,
  toStockFormValues,
  toStockInput,
  toSubmitErrors,
} from './adminForms';
import { DUPLICATE_SLUG_TYPE, VALIDATION_TYPE, VERSION_CONFLICT_TYPE } from './adminSchemas';
import type { Problem } from './problem';

const product = adminProduct(7, 'Oak chair');

describe('form values', () => {
  it('are built from a product, with the price in euros', () => {
    expect(toProductFormValues(product)).toEqual({
      slug: 'oak-chair',
      name: 'Oak chair',
      description: 'Oak chair description',
      price: '129.99',
      categorySlug: 'chairs',
      active: true,
      version: 3,
    });
    expect(toStockFormValues(product)).toEqual({ onHand: '5', version: 7 });
  });

  it('become request bodies with the price in minor units', () => {
    const values = { ...toProductFormValues(product), price: '12.3', active: false };

    expect(toProductInput(values)).toEqual({
      slug: 'oak-chair',
      name: 'Oak chair',
      description: 'Oak chair description',
      priceMinor: 1230,
      categorySlug: 'chairs',
      active: false,
    });
    expect(toProductUpdateInput(values)).toEqual({
      name: 'Oak chair',
      description: 'Oak chair description',
      priceMinor: 1230,
      categorySlug: 'chairs',
      active: false,
      version: 3,
    });
    expect(toStockInput({ onHand: '12', version: 4 })).toEqual({ onHand: 12, version: 4 });
  });
});

function problem(type: string | undefined, errors: Problem['errors'] = []): Problem {
  return { type, errors };
}

describe('toSubmitErrors', () => {
  it('maps a version conflict to the conflict message', () => {
    expect(toSubmitErrors({ status: 409, problem: problem(VERSION_CONFLICT_TYPE) }, {})).toEqual({
      form: CONFLICT_MESSAGE,
      fields: {},
    });
  });

  it('places field messages under the form fields', () => {
    const errors = [
      { field: 'priceMinor', message: 'too high' },
      { field: 'name', message: 'blank' },
    ];

    expect(
      toSubmitErrors(
        { status: 400, problem: problem(VALIDATION_TYPE, errors) },
        PRODUCT_SERVER_FIELDS,
      ),
    ).toEqual({ fields: { price: 'too high', name: 'blank' } });
  });

  it('places a duplicate slug under the slug field', () => {
    expect(
      toSubmitErrors(
        {
          status: 409,
          problem: problem(DUPLICATE_SLUG_TYPE, [{ field: 'slug', message: 'taken' }]),
        },
        PRODUCT_SERVER_FIELDS,
      ),
    ).toEqual({ fields: { slug: 'taken' } });
  });

  it('sends unknown fields to the form alert and keeps the known ones', () => {
    const errors = [
      { field: 'onHand', message: 'below reserved' },
      { field: 'version', message: 'bad' },
      { field: 'extra', message: 'worse' },
    ];

    expect(
      toSubmitErrors(
        { status: 400, problem: problem(VALIDATION_TYPE, errors) },
        STOCK_SERVER_FIELDS,
      ),
    ).toEqual({ form: 'bad worse', fields: { onHand: 'below reserved' } });
  });

  it('asks to check the form when a validation problem names no field', () => {
    expect(toSubmitErrors({ status: 400, problem: problem(VALIDATION_TYPE) }, {})).toEqual({
      form: 'Check the form and try again.',
      fields: {},
    });
  });

  it.each(['Your access cannot be confirmed right now', 'The product is busy; try again shortly'])(
    'shows the API detail %j for a 503',
    (detail) => {
      expect(
        toSubmitErrors({ status: 503, problem: { type: 'about:blank', detail, errors: [] } }, {}),
      ).toEqual({ form: detail, fields: {} });
    },
  );

  it('tells how long to wait after a 429', () => {
    expect(
      toSubmitErrors({ status: 429, problem: problem(undefined), retryAfterSeconds: 9 }, {}),
    ).toEqual({ form: 'Too many attempts. Try again in 9 seconds.', fields: {} });
  });

  it('tells to wait a moment after a 429 without Retry-After', () => {
    expect(toSubmitErrors({ status: 429, problem: problem(undefined) }, {})).toEqual({
      form: 'Too many attempts. Try again in a moment.',
      fields: {},
    });
  });

  it('ignores a detail on other statuses', () => {
    expect(toSubmitErrors({ status: 500, problem: { detail: 'secret', errors: [] } }, {})).toEqual({
      form: 'The change could not be saved. Try again.',
      fields: {},
    });
  });

  it.each([
    [0, 'The request could not be sent. Check your connection and try again.'],
    [401, 'Your session has ended. Sign in again.'],
    [403, 'You do not have permission to do this.'],
    [503, 'The shop is temporarily unavailable. Try again in a moment.'],
    [500, 'The change could not be saved. Try again.'],
  ])('shows a message for status %d', (status, message) => {
    expect(toSubmitErrors({ status, problem: problem(undefined) }, {})).toEqual({
      form: message,
      fields: {},
    });
  });
});
