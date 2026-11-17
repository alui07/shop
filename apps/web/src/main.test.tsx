import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

beforeEach(() => {
  vi.resetModules();
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({ status: 'ok', version: '9.9.9' }), {
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    ),
  );
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
});

describe('main', () => {
  it('renders the app into #root', async () => {
    document.body.innerHTML = '<div id="root"></div>';

    await import('./main');

    const root = document.getElementById('root');
    await vi.waitFor(() => expect(root?.textContent).toContain('9.9.9'));
    expect(root?.querySelector('h1')?.textContent).toBe('Shop');
    expect(root?.querySelector('[role="alert"]')).toBeNull();
  });

  it('throws when #root is missing', async () => {
    await expect(import('./main')).rejects.toThrow('Missing #root element');
  });
});
