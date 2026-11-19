import { fireEvent, screen } from '@testing-library/react';
import { adminMe, adminProduct, adminProductPage, categories } from '@/test/fixtures';
import { jsonResponse, stubFetch, type Responder } from '@/test/fetchStub';

export const VALIDATION = 'tag:shop.dev,2026:validation';
export const DUPLICATE = 'tag:shop.dev,2026:duplicate-slug';
export const CONFLICT = 'tag:shop.dev,2026:version-conflict';

export function problem(
  status: number,
  type: string,
  errors: { field: string; message: string }[] = [],
  detail = 'Problem',
) {
  return jsonResponse({ type, status, detail, errors }, status);
}

export function bodyOf(fetchMock: ReturnType<typeof stubFetch>, method: string, pathname: string) {
  const request = fetchMock.mock.calls
    .map(([call]) => call)
    .filter((call) => call.method === method && new URL(call.url).pathname === pathname);
  return Promise.all(
    request.map((call) => call.clone().json() as Promise<Record<string, unknown>>),
  );
}

export function stubAdminApi(overrides: Record<string, Responder> = {}) {
  return stubFetch({
    '/api/me': () => jsonResponse(adminMe),
    '/api/categories': () => jsonResponse(categories),
    '/api/admin/products': () => jsonResponse(adminProductPage([adminProduct(7, 'Oak chair')])),
    '/api/admin/products/7': () => jsonResponse(adminProduct(7, 'Oak chair')),
    ...overrides,
  });
}

export function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}
