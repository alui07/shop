import type { ProblemError } from '@/api/problemError';

export const IN_PROGRESS_TYPE = 'tag:shop.dev,2026:idempotency-in-progress';

const STATUS_MESSAGES: Record<number, string> = {
  0: 'The request could not be sent. Check your connection and try again.',
  401: 'Your session has ended. Sign in again.',
  403: 'You do not have permission to do this.',
  503: 'The shop is temporarily unavailable. Try again in a moment.',
};

export function tooManyAttemptsMessage(retryAfterSeconds?: number): string {
  let wait = 'a moment';
  if (retryAfterSeconds !== undefined) {
    wait = `${retryAfterSeconds} ${retryAfterSeconds === 1 ? 'second' : 'seconds'}`;
  }
  return `Too many attempts. Try again in ${wait}.`;
}

/** Text for a rejected write; the API's 503 details are fixed server texts, so they are safe to show. */
export function failureMessage(error: ProblemError, fallback: string): string {
  if (error.problem.type === IN_PROGRESS_TYPE) {
    return 'Your order is still being processed. Try again in a moment.';
  }
  if (error.status === 429) return tooManyAttemptsMessage(error.retryAfterSeconds);
  const serverText = error.status === 503 ? error.problem.detail : undefined;
  return serverText ?? STATUS_MESSAGES[error.status] ?? fallback;
}
