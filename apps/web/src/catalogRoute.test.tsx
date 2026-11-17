import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { categories, oakChair, pineChair, productPage, productSummary } from '@/test/fixtures';
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
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function stubCatalog(products: Responder = () => jsonResponse(productPage([oakChair, pineChair]))) {
  return stubFetch({
    '/api/categories': () => jsonResponse(categories),
    '/api/products': products,
  });
}

function productQueries(fetchMock: ReturnType<typeof stubCatalog>): string[] {
  return urlsRequested(fetchMock, '/api/products').map((url) => url.search);
}

describe('catalog route', () => {
  it('lists products with names, categories and formatted prices', async () => {
    const fetchMock = stubCatalog();
    renderApp('/');

    const cards = await screen.findAllByRole('article');

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Products');
    expect(cards).toHaveLength(2);
    const first = within(cards[0] as HTMLElement);
    expect(first.getByRole('link', { name: 'Oak chair' }).getAttribute('href')).toBe(
      '/products/oak-chair',
    );
    expect(first.getByText('Chairs')).toBeTruthy();
    expect(first.getByText('€129.99')).toBeTruthy();
    expect(document.title).toBe('Products | Shop');
    expect(productQueries(fetchMock)).toEqual(['']);
  });

  it('offers the labelled controls', async () => {
    stubCatalog();
    renderApp('/');
    await screen.findAllByRole('article');

    const category = screen.getByLabelText('Category');
    const optionNames = within(category)
      .getAllByRole('option')
      .map((option) => option.textContent);
    const sort = screen.getByLabelText('Sort by');

    expect(optionNames).toEqual(['All categories', 'Chairs', 'Lamps']);
    expect(
      within(sort)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['Relevance', 'Name', 'Price: low to high', 'Price: high to low']);
    expect(screen.getByRole('searchbox', { name: 'Search products' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Search' })).toBeTruthy();
    expect(screen.getByText('Search matches whole words, not parts of words.')).toBeTruthy();
  });

  it('turns URL params into an API request and reflects them in the controls', async () => {
    const fetchMock = stubCatalog();
    renderApp('/?q=chair&category=chairs&sort=price_asc&page=3');
    await screen.findAllByRole('article');

    expect(productQueries(fetchMock)).toEqual(['?q=chair&category=chairs&sort=price_asc&page=2']);
    expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('chair');
    expect((screen.getByLabelText('Category') as HTMLSelectElement).value).toBe('chairs');
    expect((screen.getByLabelText('Sort by') as HTMLSelectElement).value).toBe('price_asc');
  });

  it('falls back to defaults for invalid params', async () => {
    const fetchMock = stubCatalog();
    renderApp('/?q=%20&sort=cheapest&page=banana&category=');
    await screen.findAllByRole('article');

    expect(productQueries(fetchMock)).toEqual(['']);
    expect((screen.getByLabelText('Sort by') as HTMLSelectElement).value).toBe('name');
  });

  it('shows relevance as the sort the API applies to a search without a chosen sort', async () => {
    stubCatalog();
    renderApp('/?q=oak');
    await screen.findAllByRole('article');

    expect((screen.getByLabelText('Sort by') as HTMLSelectElement).value).toBe('relevance');
  });

  it('searches on submit, keeps other filters, resets the page and strips defaults', async () => {
    const fetchMock = stubCatalog();
    const router = renderApp('/?category=lamps&page=4');
    await screen.findAllByRole('article');

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '  desk lamp ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(router.state.location.href).toBe('/?category=lamps&q=desk+lamp'));
    await waitFor(() => expect(productQueries(fetchMock)).toHaveLength(2));
    expect(productQueries(fetchMock)[1]).toBe('?q=desk%20lamp&category=lamps');
  });

  it('does not search on typing alone', async () => {
    const fetchMock = stubCatalog();
    renderApp('/');
    await screen.findAllByRole('article');

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'lamp' } });
    await new Promise((resolve) => setTimeout(resolve, 400));

    expect(productQueries(fetchMock)).toHaveLength(1);
  });

  it('clears the search when submitted empty', async () => {
    const router = renderApp('/?q=lamp');
    stubCatalog();
    await screen.findAllByRole('article');

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '   ' } });
    fireEvent.submit(screen.getByRole('search'));

    await waitFor(() => expect(router.state.location.href).toBe('/'));
  });

  it('filters by category and clears the filter', async () => {
    const fetchMock = stubCatalog();
    const router = renderApp('/?page=2');
    await screen.findAllByRole('article');

    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'lamps' } });
    await waitFor(() => expect(router.state.location.href).toBe('/?category=lamps'));
    await waitFor(() => expect(productQueries(fetchMock).at(-1)).toBe('?category=lamps'));

    fireEvent.change(screen.getByLabelText('Category'), { target: { value: '' } });
    await waitFor(() => expect(router.state.location.href).toBe('/'));
  });

  it('sorts and keeps the choice in the URL', async () => {
    const fetchMock = stubCatalog();
    const router = renderApp('/?page=2');
    await screen.findAllByRole('article');

    fireEvent.change(screen.getByLabelText('Sort by'), { target: { value: 'price_desc' } });

    await waitFor(() => expect(router.state.location.href).toBe('/?sort=price_desc'));
    await waitFor(() => expect(productQueries(fetchMock).at(-1)).toBe('?sort=price_desc'));
  });

  it('restores the search box when the user goes back', async () => {
    const router = renderApp('/?q=chair');
    stubCatalog();
    await screen.findAllByRole('article');

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'lamp' } });
    fireEvent.submit(screen.getByRole('search'));
    await waitFor(() => expect(router.state.location.href).toBe('/?q=lamp'));
    router.history.back();

    await waitFor(() =>
      expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('chair'),
    );
  });

  it('opens a product from its card', async () => {
    stubFetch({
      '/api/categories': () => jsonResponse(categories),
      '/api/products': () => jsonResponse(productPage([oakChair])),
      '/api/products/oak-chair': () => jsonResponse({ ...oakChair, description: 'Solid oak.' }),
    });
    const router = renderApp('/');

    fireEvent.click(await screen.findByRole('link', { name: 'Oak chair' }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Oak chair'),
    );
    expect(router.state.location.pathname).toBe('/products/oak-chair');
  });
});

describe('catalog states', () => {
  it('shows an empty result message without pagination', async () => {
    stubCatalog(() => jsonResponse(productPage([], { totalPages: 0 })));
    renderApp('/?q=zzz');

    expect(await screen.findByText('No products match')).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).toBeNull();
  });

  it('shows a page-past-the-end message with a way back', async () => {
    stubCatalog(() => jsonResponse(productPage([], { page: 8, totalPages: 3, totalItems: 30 })));
    const router = renderApp('/?q=chair&page=9');

    expect(await screen.findByText(/No products on this page/)).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).toBeNull();
    fireEvent.click(screen.getByRole('link', { name: 'Back to first page' }));

    await waitFor(() => expect(router.state.location.href).toBe('/?q=chair'));
  });

  it('shows a loading message while products are pending', async () => {
    let release: () => void = () => undefined;
    stubCatalog(
      () =>
        new Promise((resolve) => {
          release = () => resolve(jsonResponse(productPage([oakChair])));
        }),
    );
    renderApp('/');

    expect((await screen.findByRole('status')).textContent).toBe('Loading products');
    release();
    expect(await screen.findByRole('article')).toBeTruthy();
  });

  it('shows an error with a retry that recovers', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    let calls = 0;
    stubCatalog(() => {
      calls += 1;
      return calls === 1 ? problemResponse(500) : jsonResponse(productPage([oakChair]));
    });
    renderApp('/');

    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe(
      'Something went wrong',
    );
    expect(document.title).toBe('Something went wrong | Shop');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('link', { name: 'Oak chair' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  });
});

describe('pagination', () => {
  const manyPages = () =>
    stubCatalog((url) => {
      const page = Number(url.searchParams.get('page') ?? 0) + 1;
      return jsonResponse(
        productPage([productSummary(`p${page}`, `Product ${page}`)], {
          page: page - 1,
          totalPages: 8,
          totalItems: 96,
        }),
      );
    });

  it('marks the current page and offers Previous and Next', async () => {
    manyPages();
    renderApp('/?page=4');

    const nav = await screen.findByRole('navigation', { name: 'Pagination' });

    expect(within(nav).getByText('4').getAttribute('aria-current')).toBe('page');
    expect(
      within(nav)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual(['Previous', '2', '3', '5', '6', 'Next']);
    expect(
      within(nav).getByRole('link', { name: 'Previous' }).getAttribute('aria-current'),
    ).toBeNull();
  });

  it('moves forward, back and to a numbered page, keeping filters', async () => {
    manyPages();
    const router = renderApp('/?q=chair&page=4');
    const nav = await screen.findByRole('navigation', { name: 'Pagination' });

    fireEvent.click(within(nav).getByRole('link', { name: 'Next' }));
    await waitFor(() => expect(router.state.location.href).toBe('/?q=chair&page=5'));

    fireEvent.click(
      within(await screen.findByRole('navigation', { name: 'Pagination' })).getByRole('link', {
        name: 'Previous',
      }),
    );
    await waitFor(() => expect(router.state.location.href).toBe('/?q=chair&page=4'));

    fireEvent.click(
      within(await screen.findByRole('navigation', { name: 'Pagination' })).getByRole('link', {
        name: '2',
      }),
    );
    await waitFor(() => expect(router.state.location.href).toBe('/?q=chair&page=2'));
  });

  it('drops the page parameter when going to page 1', async () => {
    manyPages();
    const router = renderApp('/?page=2');
    const nav = await screen.findByRole('navigation', { name: 'Pagination' });

    fireEvent.click(within(nav).getByRole('link', { name: 'Previous' }));

    await waitFor(() => expect(router.state.location.href).toBe('/'));
  });

  it('disables Previous on the first page and Next on the last', async () => {
    manyPages();
    renderApp('/');
    const nav = await screen.findByRole('navigation', { name: 'Pagination' });
    expect(within(nav).queryByRole('link', { name: 'Previous' })).toBeNull();
    expect(within(nav).getByText('Previous').getAttribute('aria-disabled')).toBe('true');
    expect(within(nav).getByRole('link', { name: 'Next' })).toBeTruthy();
    cleanup();

    manyPages();
    renderApp('/?page=8');
    const lastNav = await screen.findByRole('navigation', { name: 'Pagination' });
    expect(within(lastNav).queryByRole('link', { name: 'Next' })).toBeNull();
    expect(within(lastNav).getByText('Next').getAttribute('aria-disabled')).toBe('true');
  });
});
