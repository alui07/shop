import type { Page } from '@playwright/test';
import { expect, frontDoorOrigin, idpOrigin } from './test';

export type Role = 'customer' | 'admin';

const accounts: Record<Role, { email: string; passwordVariable: string }> = {
  customer: { email: 'customer@example.test', passwordVariable: 'SHOP_TEST_CUSTOMER_PASSWORD' },
  admin: { email: 'admin@example.test', passwordVariable: 'SHOP_TEST_ADMIN_PASSWORD' },
};

export const sessionCookieName = '__Host-SESSION';
export const csrfCookieName = '__Host-XSRF-TOKEN';

function passwordOf(role: Role): string {
  const password = process.env[accounts[role].passwordVariable];
  if (!password) {
    throw new Error(`${accounts[role].passwordVariable} must be set for the e2e runner`);
  }
  return password;
}

export function signInLink(page: Page) {
  return page.getByRole('banner').getByRole('link', { name: 'Sign in' });
}

export function signOutButton(page: Page) {
  return page.getByRole('button', { name: 'Sign out' });
}

// Label-based locators keep this the only place that knows the Keycloak login form.
export async function fillKeycloakForm(page: Page, role: Role): Promise<void> {
  await expect(page).toHaveURL(new RegExp(`^${idpOrigin}/realms/shop/`));
  await page.getByLabel(/email/i).fill(accounts[role].email);
  await page.getByLabel('Password', { exact: true }).fill(passwordOf(role));
  await page.getByRole('button', { name: 'Sign in' }).click();
}

// Starts at the current page, signs in through the header link and returns once the app shows the signed-in header.
export async function signIn(page: Page, role: Role, startPath = '/'): Promise<void> {
  await page.goto(startPath);
  await signInLink(page).click();
  await fillKeycloakForm(page, role);
  await expect(signOutButton(page)).toBeVisible();
  expect(new URL(page.url()).origin).toBe(frontDoorOrigin);
}

export async function signOut(page: Page): Promise<void> {
  await signOutButton(page).click();
  await expect(signInLink(page)).toBeVisible();
}

export interface Me {
  authenticated: boolean;
  subject: string | null;
  name: string | null;
  email: string | null;
  roles: string[];
}

export interface BrowserResponse {
  status: number;
  contentType: string;
  body: string;
}

// A same-origin fetch from the page, so the real session and CSRF cookies travel as in the SPA.
export async function apiFetch(
  page: Page,
  path: string,
  options: { method?: string; csrfToken?: string | null; json?: unknown } = {},
): Promise<BrowserResponse> {
  return page.evaluate(
    async ({ path: target, method, csrfToken, json }) => {
      const headers: Record<string, string> = {};
      if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;
      if (json !== undefined) headers['Content-Type'] = 'application/json';
      const response = await fetch(target, {
        method,
        headers,
        redirect: 'manual',
        body: json === undefined ? undefined : JSON.stringify(json),
      });
      return {
        status: response.status,
        contentType: response.headers.get('content-type') ?? '',
        body: await response.text(),
      };
    },
    {
      path,
      method: options.method ?? 'GET',
      csrfToken: options.csrfToken ?? null,
      json: options.json,
    },
  );
}

export async function fetchMe(page: Page): Promise<Me> {
  const response = await apiFetch(page, '/api/me');
  expect(response.status).toBe(200);
  return JSON.parse(response.body) as Me;
}

export async function csrfTokenOf(page: Page): Promise<string> {
  const cookies = await page.context().cookies(frontDoorOrigin);
  const token = cookies.find((cookie) => cookie.name === csrfCookieName)?.value;
  expect(token, `${csrfCookieName} cookie`).toBeTruthy();
  return token ?? '';
}
