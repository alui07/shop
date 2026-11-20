import { problemSchema, type Problem } from '@/lib/problem';

/** A rejected write; `status` is 0 when the request never got an answer. */
export class ProblemError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(`Request failed with status ${status}`);
    this.name = 'ProblemError';
    this.status = status;
    this.problem = problem;
  }
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
    throw new ProblemError(result.response.status, parsed.success ? parsed.data : { errors: [] });
  }
  return result.data;
}
