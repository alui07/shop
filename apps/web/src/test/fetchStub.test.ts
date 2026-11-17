import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, problemResponse, stubFetch, urlsRequested } from './fetchStub';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('stubFetch', () => {
  it('answers by pathname and records the requested URLs', async () => {
    const fetchMock = stubFetch({ '/api/ok': () => jsonResponse({ ok: true }) });

    const response = await fetch(new Request('http://localhost/api/ok?x=1'));

    expect(await response.json()).toEqual({ ok: true });
    expect(urlsRequested(fetchMock, '/api/ok').map((url) => url.search)).toEqual(['?x=1']);
    expect(urlsRequested(fetchMock, '/api/other')).toEqual([]);
  });

  it('rejects requests nobody registered', async () => {
    stubFetch({});

    await expect(fetch(new Request('http://localhost/api/none'))).rejects.toThrow(
      'Unexpected request to /api/none',
    );
  });

  it('builds problem detail responses', async () => {
    const response = problemResponse(404, 'Not here');

    expect(response.status).toBe(404);
    expect(response.headers.get('Content-Type')).toBe('application/problem+json');
    expect(await response.json()).toMatchObject({ title: 'Not here', status: 404 });
    expect(problemResponse(500).status).toBe(500);
  });
});
