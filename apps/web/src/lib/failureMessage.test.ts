import { describe, expect, it } from 'vitest';
import { ProblemError } from '@/api/problemError';
import { failureMessage, IN_PROGRESS_TYPE } from './failureMessage';

const FALLBACK = 'fallback text';

describe('failureMessage', () => {
  it.each([
    [0, 'The request could not be sent. Check your connection and try again.'],
    [401, 'Your session has ended. Sign in again.'],
    [403, 'You do not have permission to do this.'],
    [503, 'The shop is temporarily unavailable. Try again in a moment.'],
    [500, FALLBACK],
    [413, FALLBACK],
  ])('maps status %d', (status, message) => {
    expect(failureMessage(new ProblemError(status, { errors: [] }), FALLBACK)).toBe(message);
  });

  it('shows the detail of a 503', () => {
    expect(
      failureMessage(new ProblemError(503, { detail: 'Busy, retry', errors: [] }), FALLBACK),
    ).toBe('Busy, retry');
  });

  it('explains an order that is still being processed', () => {
    expect(
      failureMessage(new ProblemError(409, { type: IN_PROGRESS_TYPE, errors: [] }), FALLBACK),
    ).toBe('Your order is still being processed. Try again in a moment.');
  });
});
