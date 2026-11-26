import { describe, expect, it } from 'vitest';
import { ProblemError, sendRequest } from './problemError';

const response = (status: number, headers?: Record<string, string>) =>
  new Response(null, { status, headers });

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

  it.each([
    ['7', 7],
    [' 30 ', 30],
    ['Wed, 21 Oct 2026 07:28:00 GMT', undefined],
    ['0', undefined],
    ['-5', undefined],
    ['1.5', undefined],
    ['soon', undefined],
  ])('reads Retry-After %j as %s', async (header, expected) => {
    const failure = await sendRequest(
      Promise.resolve({ error: {}, response: response(429, { 'Retry-After': header }) }),
    ).catch((error: unknown) => error);

    expect(failure).toMatchObject({ status: 429 });
    expect((failure as ProblemError).retryAfterSeconds).toBe(expected);
  });

  it('leaves retryAfterSeconds absent without the header', async () => {
    const failure = await sendRequest(
      Promise.resolve({ error: {}, response: response(429) }),
    ).catch((error: unknown) => error);

    expect((failure as ProblemError).retryAfterSeconds).toBeUndefined();
  });
});
