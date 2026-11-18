import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { anonymousMe, categories, oakChair, productPage } from '@/test/fixtures';
import { jsonResponse, stubFetch } from '@/test/fetchStub';

beforeEach(() => {
  vi.resetModules();
  stubFetch({
    '/api/me': () => jsonResponse(anonymousMe),
    '/api/categories': () => jsonResponse(categories),
    '/api/products': () => jsonResponse(productPage([oakChair])),
  });
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
});

describe('main', () => {
  it('renders the catalog into #root', async () => {
    document.body.innerHTML = '<div id="root"></div>';

    await import('./main');

    const root = document.getElementById('root');
    await vi.waitFor(() => expect(root?.textContent).toContain('Oak chair'));
    expect(root?.querySelector('h1')?.textContent).toBe('Products');
  });

  it('throws when #root is missing', async () => {
    await expect(import('./main')).rejects.toThrow('Missing #root element');
  });
});
