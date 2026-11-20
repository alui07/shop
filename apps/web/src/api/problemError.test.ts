import { describe, expect, it } from 'vitest';
import { ProblemError, sendRequest } from './problemError';

const response = (status: number) => new Response(null, { status });

describe('sendRequest', () => {
  it('returns the data of a successful call', async () => {
    expect(
      await sendRequest(Promise.resolve({ data: { ok: true }, response: response(200) })),
    ).toEqual({
      ok: true,
    });
  });

  it('reports the parsed problem of a rejected call', async () => {
    const failure = await sendRequest(
      Promise.resolve({ error: { type: 'x', errors: [] }, response: response(409) }),
    ).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ProblemError);
    expect(failure).toMatchObject({ status: 409, problem: { type: 'x', errors: [] } });
  });

  it('reports an empty problem when the body is not a problem document', async () => {
    const failure = await sendRequest(
      Promise.resolve({ error: 'oops', response: response(502) }),
    ).catch((error: unknown) => error);

    expect(failure).toMatchObject({ status: 502, problem: { errors: [] } });
  });

  it('reports status 0 when no answer arrived', async () => {
    const failure = await sendRequest(Promise.reject(new TypeError('offline'))).catch(
      (error: unknown) => error,
    );

    expect(failure).toMatchObject({ status: 0, problem: { errors: [] } });
  });
});
