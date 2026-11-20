import { describe, expect, it } from 'vitest';
import { effectiveStatus, STATUS_LABELS } from './orderStatus';

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
  expect(Object.values(STATUS_LABELS)).toEqual(['Awaiting payment', 'Cancelled', 'Expired']);
});
