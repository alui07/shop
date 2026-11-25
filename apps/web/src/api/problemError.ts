import { problemSchema, type Problem } from '@/lib/problem';

/** A rejected write; `status` is 0 when the request never got an answer. */
export class ProblemError extends Error {
  readonly status: number;
  readonly problem: Problem;
  /** Seconds from a delta-seconds `Retry-After` header; absent for an HTTP-date or invalid value. */
  readonly retryAfterSeconds?: number;

  constructor(status: number, problem: Problem, retryAfterSeconds?: number) {
    super(`Request failed with status ${status}`);
    this.name = 'ProblemError';
    this.status = status;
    this.problem = problem;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Delta-seconds of at least 1; an HTTP-date, a negative, a fraction or 0 counts as absent. */
export function parseRetryAfter(header: string | null): number | undefined {
  const value = header?.trim();
  if (value === undefined || !/^\d+$/.test(value)) return undefined;
  const seconds = Number(value);
  return seconds >= 1 ? seconds : undefined;
}

export async function sendRequest<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  let result;
  try {
    result = await call;
  } catch {
    throw new ProblemError(0, { errors: [] });
  }
  if (result.data === undefined) {
    const parsed = problemSchema.safeParse(result.error);
    throw new ProblemError(
      result.response.status,
      parsed.success ? parsed.data : { errors: [] },
      parseRetryAfter(result.response.headers.get('Retry-After')),
    );
  }
  return result.data;
}
