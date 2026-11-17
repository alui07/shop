import { vi } from 'vitest';

export type Responder = (url: URL, request: Request) => Response | Promise<Response>;

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export function problemResponse(status: number, title = 'Problem'): Response {
  return new Response(JSON.stringify({ type: 'about:blank', title, status, detail: title }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

/** Stubs global fetch; responders are keyed by URL pathname and receive the Request the client sends. */
export function stubFetch(responders: Record<string, Responder>) {
  const fetchMock = vi.fn(async (request: Request) => {
    const url = new URL(request.url);
    const responder = responders[url.pathname];
    if (!responder) throw new TypeError(`Unexpected request to ${url.pathname}`);
    return responder(url, request);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

export function urlsRequested(fetchMock: ReturnType<typeof stubFetch>, pathname: string): URL[] {
  return fetchMock.mock.calls
    .map(([request]) => new URL(request.url))
    .filter((url) => url.pathname === pathname);
}
