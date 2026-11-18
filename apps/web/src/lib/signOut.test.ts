import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse } from '@/test/fetchStub';
import { signOut } from './signOut';

const assign = vi.fn();

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  assign.mockReset();
});

function stubLocation() {
  vi.stubGlobal('location', { ...window.location, assign });
}

describe('signOut', () => {
  it('posts to /logout with the CSRF header, then follows the redirect URL', async () => {
    vi.spyOn(document, 'cookie', 'get').mockReturnValue('__Host-XSRF-TOKEN=tok');
    stubLocation();
    const fetchMock = vi.fn(async () =>
      jsonResponse({ redirectUrl: 'http://idp.test/logout?x=1' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    expect(await signOut()).toBe(true);

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/logout');
    expect(init.method).toBe('POST');
    expect(init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'tok' });
    expect(assign).toHaveBeenCalledWith('http://idp.test/logout?x=1');
  });

  it('fails without navigating on an error status', async () => {
    stubLocation();
    vi.stubGlobal('fetch', async () => jsonResponse({}, 403));

    expect(await signOut()).toBe(false);
    expect(assign).not.toHaveBeenCalled();
  });

  it.each([
    ['missing', {}],
    ['not a string', { redirectUrl: 5 }],
    ['not a URL', { redirectUrl: 'nope' }],
    ['a script URL', { redirectUrl: 'javascript:alert(1)' }],
  ])('refuses a redirect URL that is %s', async (_name, body) => {
    stubLocation();
    vi.stubGlobal('fetch', async () => jsonResponse(body));

    expect(await signOut()).toBe(false);
    expect(assign).not.toHaveBeenCalled();
  });

  it('tolerates a network failure', async () => {
    stubLocation();
    vi.stubGlobal('fetch', async () => {
      throw new TypeError('offline');
    });

    expect(await signOut()).toBe(false);
  });
});
