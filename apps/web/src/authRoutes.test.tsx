import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  anonymousMe,
  adminMe,
  categories,
  customerMe,
  oakChair,
  productPage,
} from '@/test/fixtures';
import { jsonResponse, problemResponse, stubFetch } from '@/test/fetchStub';
import { renderApp } from '@/test/renderApp';

const assign = vi.fn();

beforeEach(() => {
  window.sessionStorage.clear();
});

afterEach(() => {
  window.history.replaceState(null, '', '/');
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  assign.mockReset();
});

function stubShop(me: Response | (() => Response), extra: Parameters<typeof stubFetch>[0] = {}) {
  return stubFetch({
    '/api/me': () => (typeof me === 'function' ? me() : me.clone()),
    '/api/categories': () => jsonResponse(categories),
    '/api/products': () => jsonResponse(productPage([oakChair])),
    ...extra,
  });
}

describe('header', () => {
  it('shows nothing about the session until it is known', async () => {
    stubFetch({
      '/api/me': () => new Promise<Response>(() => undefined),
      '/api/categories': () => jsonResponse(categories),
      '/api/products': () => jsonResponse(productPage([oakChair])),
    });
    renderApp('/');

    await screen.findByRole('navigation', { name: 'Main' });

    expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull();
  });

  it('offers a plain link to sign in for anonymous visitors', async () => {
    stubShop(jsonResponse(anonymousMe));
    renderApp('/');

    const link = await screen.findByRole('link', { name: 'Sign in' });

    expect(link.getAttribute('href')).toBe('/oauth2/authorization/keycloak');
    expect(screen.queryByRole('link', { name: 'Admin' })).toBeNull();
  });

  it('offers sign in when the session cannot be read', async () => {
    stubShop(() => problemResponse(500));
    renderApp('/');

    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeTruthy();
  });

  it('saves where the visitor was before leaving for the sign-in page', async () => {
    stubShop(jsonResponse(anonymousMe));
    window.history.replaceState(null, '', '/status?from=header#top');
    renderApp('/status');
    const link = await screen.findByRole('link', { name: 'Sign in' });
    const navigations = vi.fn((event: Event) => event.preventDefault());
    document.addEventListener('click', navigations);

    fireEvent.click(link);
    document.removeEventListener('click', navigations);

    expect(navigations).toHaveBeenCalled();
    expect(window.sessionStorage.getItem('shop.returnTo')).toBe('/status?from=header#top');
  });

  it('shows a customer by name without an Admin link', async () => {
    stubShop(jsonResponse(customerMe));
    renderApp('/');

    expect((await screen.findByTestId('user-name')).textContent).toBe('Casey Customer');
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Admin' })).toBeNull();
  });

  it('falls back to the email, then a generic label', async () => {
    stubShop(jsonResponse({ ...customerMe, name: null }));
    renderApp('/');
    expect((await screen.findByTestId('user-name')).textContent).toBe('casey@example.test');
    cleanup();

    stubShop(jsonResponse({ ...customerMe, name: null, email: null }));
    renderApp('/');
    expect((await screen.findByTestId('user-name')).textContent).toBe('Signed in');
  });

  it('shows an Admin link to admins', async () => {
    stubShop(jsonResponse(adminMe));
    renderApp('/');

    expect((await screen.findByRole('link', { name: 'Admin' })).getAttribute('href')).toBe(
      '/admin',
    );
  });
});

describe('sign out', () => {
  it('posts to /logout with the CSRF token and leaves for the identity provider', async () => {
    vi.spyOn(document, 'cookie', 'get').mockReturnValue('__Host-XSRF-TOKEN=tok');
    vi.stubGlobal('location', { ...window.location, assign });
    stubShop(jsonResponse(adminMe));
    renderApp('/');
    const button = await screen.findByRole('button', { name: 'Sign out' });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ redirectUrl: 'http://idp.test/logout' })),
    );

    fireEvent.click(button);

    await waitFor(() => expect(assign).toHaveBeenCalledWith('http://idp.test/logout'));
    const [url, init] = vi.mocked(fetch).mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/logout');
    expect(init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'tok' });
  });

  it('reports a failure, refreshes the session and can be retried', async () => {
    let meCalls = 0;
    stubShop(() => {
      meCalls += 1;
      return jsonResponse(adminMe);
    });
    renderApp('/');
    const button = await screen.findByRole('button', { name: 'Sign out' });
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) =>
        String(input) === '/logout' ? problemResponse(500) : jsonResponse(adminMe),
      ),
    );

    fireEvent.click(button);

    expect((await screen.findByRole('alert')).textContent).toBe('Sign out failed. Try again.');
    expect(meCalls).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    await screen.findByRole('alert');
  });
});

describe('sign-in result', () => {
  it('shows an alert after a failed sign-in', async () => {
    stubShop(jsonResponse(anonymousMe));
    renderApp('/?login=failed');

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Sign-in failed. Please try again.',
    );
  });

  it('shows no alert otherwise', async () => {
    stubShop(jsonResponse(anonymousMe));
    renderApp('/');
    await screen.findByRole('link', { name: 'Sign in' });

    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('returns a signed-in visitor to the page they came from', async () => {
    window.sessionStorage.setItem('shop.returnTo', '/status?from=login#top');
    stubShop(jsonResponse(customerMe), {
      '/api/status': () => jsonResponse({ status: 'ok', version: '1' }),
    });
    const router = renderApp('/');

    await waitFor(() => expect(router.state.location.pathname).toBe('/status'));
    expect(router.state.location.href).toBe('/status?from=login#top');
    expect(window.sessionStorage.getItem('shop.returnTo')).toBeNull();
  });

  it('stays on the home page when that is where the visitor came from', async () => {
    window.sessionStorage.setItem('shop.returnTo', '/');
    stubShop(jsonResponse(customerMe));
    const router = renderApp('/');

    await screen.findByTestId('user-name');

    await waitFor(() => expect(window.sessionStorage.getItem('shop.returnTo')).toBeNull());
    expect(router.state.location.pathname).toBe('/');
  });

  it('ignores a saved address on another origin', async () => {
    window.sessionStorage.setItem('shop.returnTo', 'https://evil.example/');
    stubShop(jsonResponse(customerMe));
    const router = renderApp('/');

    await screen.findByTestId('user-name');

    await waitFor(() => expect(window.sessionStorage.getItem('shop.returnTo')).toBeNull());
    expect(router.state.location.pathname).toBe('/');
  });

  it('forgets the saved address when the sign-in failed', async () => {
    window.sessionStorage.setItem('shop.returnTo', '/status');
    stubShop(jsonResponse(anonymousMe));
    const router = renderApp('/?login=failed');

    await screen.findByRole('link', { name: 'Sign in' });

    await waitFor(() => expect(window.sessionStorage.getItem('shop.returnTo')).toBeNull());
    expect(router.state.location.pathname).toBe('/');
  });

  it('does not touch the saved address on other pages', async () => {
    window.sessionStorage.setItem('shop.returnTo', '/status');
    stubShop(jsonResponse(customerMe), {
      '/api/status': () => jsonResponse({ status: 'ok', version: '1' }),
    });
    renderApp('/status');

    await screen.findByTestId('user-name');

    expect(window.sessionStorage.getItem('shop.returnTo')).toBe('/status');
  });
});
