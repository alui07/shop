import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { adminMe, anonymousMe, auditEvent, auditPage, customerMe } from '@/test/fixtures';
import {
  jsonResponse,
  problemResponse,
  stubFetch,
  urlsRequested,
  type Responder,
} from '@/test/fetchStub';
import { renderApp } from '@/test/renderApp';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function stubAudit(audit: Responder, me: unknown = adminMe) {
  return stubFetch({ '/api/me': () => jsonResponse(me), '/api/admin/audit': audit });
}

const trace = '4bf92f3577b34da6a3ce929d0e0e4736';

describe('audit log', () => {
  it('lists events with the actor, action, product link, changes and an unverified trace', async () => {
    stubAudit(() =>
      jsonResponse(
        auditPage([
          auditEvent(2, {
            action: 'product.created',
            actorName: '<b>Mallory</b>',
            traceId: trace,
            entityId: 9,
            changes: [
              { field: 'name', from: null, to: 'Pine desk' },
              { field: 'active', from: 'true', to: 'false' },
            ],
          }),
          auditEvent(1),
        ]),
      ),
    );
    renderApp('/admin/audit');

    const rows = await screen.findAllByRole('row');

    expect(screen.getByRole('heading', { level: 1, name: 'Audit log' })).toBeTruthy();
    expect(document.title).toBe('Audit log | Shop');
    expect(screen.getByRole('columnheader', { name: 'Trace (unverified)' })).toBeTruthy();
    expect(rows).toHaveLength(3);
    const first = within(rows[1] as HTMLElement);
    expect(first.getByText('<b>Mallory</b>').tagName).toBe('BDI');
    expect(first.getByText('product.created')).toBeTruthy();
    expect(first.getByRole('link', { name: 'Product 9' }).getAttribute('href')).toBe(
      '/admin/products/9',
    );
    expect(first.getByText('Nov 18, 2026, 10:30:15 AM UTC')).toBeTruthy();
    expect(first.getByText(trace).tagName).toBe('CODE');
    const changes = first.getAllByRole('listitem').map((item) => item.textContent);
    expect(changes).toEqual(['name: (none) → Pine desk', 'active: true → false']);
    expect(first.getByText('Pine desk').tagName).toBe('BDI');
    const second = within(rows[2] as HTMLElement);
    expect(second.getByText('none')).toBeTruthy();
    expect(second.getByText('Oak chair').tagName).toBe('BDI');
  });

  it('shows a loading message first', async () => {
    stubAudit(() => new Promise<Response>(() => undefined));
    renderApp('/admin/audit');

    expect((await screen.findByRole('status')).textContent).toBe('Loading audit log');
  });

  it('says when nothing has been recorded', async () => {
    stubAudit(() => jsonResponse(auditPage([])));
    renderApp('/admin/audit');

    expect(await screen.findByText('No changes recorded yet')).toBeTruthy();
  });

  it('pages with 1-based URLs and 0-based API pages', async () => {
    const fetchMock = stubAudit(() => jsonResponse(auditPage([auditEvent(1)], { totalPages: 3 })));
    const router = renderApp('/admin/audit');
    await screen.findAllByRole('row');

    fireEvent.click(screen.getByRole('link', { name: 'Next' }));

    await waitFor(() => expect(router.state.location.search).toEqual({ page: '2' }));
    await waitFor(() =>
      expect(urlsRequested(fetchMock, '/api/admin/audit').map((url) => url.search)).toContain(
        '?page=1',
      ),
    );
  });

  it('shows no pagination for a single page', async () => {
    stubAudit(() => jsonResponse(auditPage([auditEvent(1)])));
    renderApp('/admin/audit');
    await screen.findAllByRole('row');

    expect(screen.queryByRole('navigation', { name: 'Pagination' })).toBeNull();
  });

  it('asks anonymous visitors to sign in and tells customers they have no access', async () => {
    const fetchMock = stubAudit(() => jsonResponse(auditPage([])), anonymousMe);
    renderApp('/admin/audit');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeTruthy();
    cleanup();

    stubAudit(() => jsonResponse(auditPage([])), customerMe);
    renderApp('/admin/audit');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'You do not have access' }),
    ).toBeTruthy();
    expect(urlsRequested(fetchMock, '/api/admin/audit')).toEqual([]);
  });

  it('handles an ended session, a revoked role and other failures', async () => {
    stubAudit(() => problemResponse(401));
    renderApp('/admin/audit');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeTruthy();
    cleanup();

    stubAudit(() => problemResponse(403));
    renderApp('/admin/audit');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'You do not have access' }),
    ).toBeTruthy();
    cleanup();

    stubAudit(() => problemResponse(500));
    renderApp('/admin/audit');
    expect((await screen.findByRole('alert')).textContent).toBe(
      'The audit log could not be loaded.',
    );
  });
});
