import {
  csrfCookieName,
  fetchMe,
  fillKeycloakForm,
  sessionCookieName,
  signIn,
  signInLink,
  signOut,
  signOutButton,
} from './support/auth';
import { expect, frontDoorOrigin, idpOrigin, test } from './support/test';

test('an anonymous visitor is not signed in and gets no session cookie', async ({
  page,
  request,
}) => {
  const response = await request.get('/api/products');
  const setCookies = response
    .headersArray()
    .filter((header) => header.name.toLowerCase() === 'set-cookie');
  expect(setCookies.filter((header) => header.value.startsWith(sessionCookieName))).toEqual([]);

  await page.goto('/');
  await expect(signInLink(page)).toBeVisible();
  expect(await fetchMe(page)).toMatchObject({ authenticated: false, roles: [] });
  const cookies = await page.context().cookies(frontDoorOrigin);
  expect(cookies.map((cookie) => cookie.name)).not.toContain(sessionCookieName);
});

test('a customer signs in and out, and the session is rotated and cleaned up', async ({ page }) => {
  await page.goto('/');
  await signInLink(page).click();
  await expect(page).toHaveURL(new RegExp(`^${idpOrigin}/realms/shop/`));
  const loginSession = (await page.context().cookies(frontDoorOrigin)).find(
    (cookie) => cookie.name === sessionCookieName,
  );

  await fillKeycloakForm(page, 'customer');
  await expect(signOutButton(page)).toBeVisible();
  expect(new URL(page.url()).origin).toBe(frontDoorOrigin);

  const me = await fetchMe(page);
  expect(me).toMatchObject({ authenticated: true, email: 'customer@example.test' });
  expect(me.roles).toEqual(['customer']);
  expect(me.subject).toBeTruthy();
  await expect(page.getByTestId('user-name')).toBeVisible();

  const cookies = await page.context().cookies(frontDoorOrigin);
  const session = cookies.find((cookie) => cookie.name === sessionCookieName);
  expect(session).toMatchObject({ httpOnly: true, secure: true, sameSite: 'Lax', path: '/' });
  expect(session?.value).not.toBe(loginSession?.value);
  const csrf = cookies.find((cookie) => cookie.name === csrfCookieName);
  expect(csrf).toMatchObject({ httpOnly: false, secure: true, sameSite: 'Strict', path: '/' });
  expect(await page.evaluate(() => document.cookie)).not.toContain(sessionCookieName);

  await signOut(page);
  expect(new URL(page.url()).origin).toBe(frontDoorOrigin);
  expect(await fetchMe(page)).toMatchObject({ authenticated: false, roles: [] });
  const after = await page.context().cookies(frontDoorOrigin);
  expect(after.find((cookie) => cookie.name === sessionCookieName)?.value).not.toBe(session?.value);

  await signInLink(page).click();
  await expect(page.getByLabel('Password', { exact: true })).toBeVisible();
  expect(new URL(page.url()).origin).toBe(idpOrigin);
});

test('an admin has the admin role and sees the Admin link', async ({ page }) => {
  await signIn(page, 'admin');

  expect((await fetchMe(page)).roles.toSorted()).toEqual(['admin', 'customer']);
  await expect(page.getByRole('banner').getByRole('link', { name: 'Admin' })).toBeVisible();
});

test('a customer does not see the Admin link', async ({ page }) => {
  await signIn(page, 'customer');

  await expect(page.getByRole('banner').getByRole('link', { name: 'Admin' })).toHaveCount(0);
});

test('signing in from a product page returns to that page', async ({ page }) => {
  await signIn(page, 'customer', '/products/oak-desk');

  await expect(page).toHaveURL(`${frontDoorOrigin}/products/oak-desk`);
  await expect(page.getByRole('heading', { level: 1, name: 'Oak Desk' })).toBeVisible();
});

test('a failed login shows an alert on the home page', async ({ page }) => {
  await page.goto('/?login=failed');

  await expect(page.getByRole('alert')).toHaveText(/Sign-in failed/);
  await expect(signInLink(page)).toBeVisible();
});

test('a wrong password stays on the Keycloak form', async ({ page }) => {
  await page.goto('/');
  await signInLink(page).click();
  await page.getByLabel(/email/i).fill('nobody@example.test');
  await page.getByLabel('Password', { exact: true }).fill('not-the-password');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByText(/invalid/i)).toBeVisible();
  expect(new URL(page.url()).origin).toBe(idpOrigin);
});

test('a visitor and a customer get the right admin page', async ({ page }) => {
  await page.goto('/admin');
  await expect(page.getByRole('main').getByRole('link', { name: 'Sign in' })).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);

  await signIn(page, 'customer', '/admin');
  await expect(page.getByText(/do not have access/i)).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});
