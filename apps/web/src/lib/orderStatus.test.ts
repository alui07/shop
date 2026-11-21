import { describe, expect, it } from 'vitest';
import {
  awaitsPaymentConfirmation,
  effectiveStatus,
  PAYMENT_STATUS_LABELS,
  STATUS_LABELS,
} from './orderStatus';

describe('effectiveStatus', () => {
  it('shows an unpaid order past its hold as expired', () => {
    expect(effectiveStatus('PENDING_PAYMENT', true)).toBe('EXPIRED');
  });

  it('keeps an unpaid order inside its hold', () => {
    expect(effectiveStatus('PENDING_PAYMENT', false)).toBe('PENDING_PAYMENT');
  });

  it('keeps a finished order as it is', () => {
    expect(effectiveStatus('CANCELLED', true)).toBe('CANCELLED');
  });
});

it('labels every status', () => {
  expect(Object.values(STATUS_LABELS)).toEqual([
    'Awaiting payment',
    'Cancelled',
    'Expired',
    'Paid',
    'Refund pending',
    'Refunded',
  ]);
});

it('labels every payment status', () => {
  expect(Object.values(PAYMENT_STATUS_LABELS)).toEqual([
    'Started',
    'Received',
    'Failed',
    'Refund pending',
    'Refunded',
  ]);
});

describe('awaitsPaymentConfirmation', () => {
  it.each([
    ['PENDING_PAYMENT', null, true],
    ['PENDING_PAYMENT', 'CREATED', true],
    ['EXPIRED', 'SUCCEEDED', true],
    ['PENDING_PAYMENT', 'FAILED', false],
    ['EXPIRED', 'FAILED', false],
    ['PAID', 'SUCCEEDED', false],
    ['CANCELLED', null, false],
  ] as const)('%s with payment %s gives %s', (status, paymentStatus, expected) => {
    expect(awaitsPaymentConfirmation({ status, paymentStatus })).toBe(expected);
  });
});
