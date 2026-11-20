import type { ProblemError } from '@/api/problemError';

export const IN_PROGRESS_TYPE = 'tag:shop.dev,2026:idempotency-in-progress';

const STATUS_MESSAGES: Record<number, string> = {
  0: 'The request could not be sent. Check your connection and try again.',
  401: 'Your session has ended. Sign in again.',
  403: 'You do not have permission to do this.',
  503: 'The shop is temporarily unavailable. Try again in a moment.',
};

/** Text for a rejected write; the API's 503 details are fixed server texts, so they are safe to show. */
export function failureMessage(error: ProblemError, fallback: string): string {
  if (error.problem.type === IN_PROGRESS_TYPE) {
    return 'Your order is still being processed. Try again in a moment.';
  }
  const serverText = error.status === 503 ? error.problem.detail : undefined;
  return serverText ?? STATUS_MESSAGES[error.status] ?? fallback;
}
