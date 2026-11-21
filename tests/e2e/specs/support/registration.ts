import type { Browser, BrowserContext, Page } from '@playwright/test';
import { fillKeycloakCredentials, signInLink, signOutButton } from './auth';
import { linkIn, waitForMail } from './mailpit';
import { expect, idpOrigin } from './test';

export interface NewAccount {
  email: string;
  password: string;
}

export interface RegisteredShopper extends NewAccount {
  context: BrowserContext;
  page: Page;
}

// Fresh per call, so parallel workers and a second run on the same Keycloak never collide.
export function newAccount(): NewAccount {
  const token = `${Date.now()}-${crypto.randomUUID().slice(0, 8)}`;
  return { email: `e2e-${token}@example.test`, password: `Pw-${crypto.randomUUID()}` };
}

// Opens Keycloak's registration form from the app's sign-in link and submits it. With email
// verification on, the form has no password; it is set after the mailed link is followed.
export async function submitRegistration(page: Page, account: NewAccount): Promise<void> {
  await page.goto('/');
  await signInLink(page).click();
  await expect(page).toHaveURL(new RegExp(`^${idpOrigin}/realms/shop/`));
  await page.getByRole('link', { name: 'Register' }).click();

  // Labels carry an aria-hidden asterisk, so they are matched by prefix.
  await page.getByRole('textbox', { name: /^Username/ }).fill(account.email);
  await page.getByRole('textbox', { name: /^Email/ }).fill(account.email);
  await page.getByRole('textbox', { name: /^First name/ }).fill('E2E');
  await page.getByRole('textbox', { name: /^Last name/ }).fill('Shopper');
  await page.getByRole('button', { name: 'Register' }).click();
}

// Follows the mailed link in this browser and sets the password, after which Keycloak returns to the app signed in.
export async function verifyAndSetPassword(page: Page, account: NewAccount): Promise<void> {
  await page.goto(await verificationLinkFor(account.email));
  await expect(page.getByRole('heading', { name: 'Update password' })).toBeVisible();
  await page.locator('#password-new').fill(account.password);
  await page.locator('#password-confirm').fill(account.password);
  await page.getByRole('button', { name: 'Submit' }).click();
  await expect(signOutButton(page)).toBeVisible();
}

export async function verificationLinkFor(email: string): Promise<string> {
  const [message] = await waitForMail(`to:"${email}"`, (m) => m.recipients.includes(email));
  const link = linkIn(message as NonNullable<typeof message>, /https?:\/\/\S+action-token\S*/);
  // Keycloak builds the link from its public hostname; the browser reaches that origin through the host mapping.
  const url = new URL(link);
  return `${idpOrigin}${url.pathname}${url.search}`;
}

export async function signInWithAccount(page: Page, account: NewAccount): Promise<void> {
  await page.goto('/');
  await signInLink(page).click();
  await fillKeycloakCredentials(page, account.email, account.password);
}

// Registers, verifies, sets the password and returns a context signed in as the new customer.
export async function registerAndVerify(browser: Browser): Promise<RegisteredShopper> {
  const account = newAccount();
  const context = await browser.newContext();
  const page = await context.newPage();
  await submitRegistration(page, account);
  await verifyAndSetPassword(page, account);
  return { ...account, context, page };
}

// Changes the address in Keycloak's account console, which the app's session does not see until the next sign-in.
export async function openAccountEmailForm(page: Page, newEmail: string): Promise<void> {
  const account = await page.context().newPage();
  await account.goto(`${idpOrigin}/realms/shop/account/`);
  await account.getByLabel('Email').fill(newEmail);
  await account.getByRole('button', { name: 'Save' }).click();
  await expect(account.getByText('Your account has been updated.')).toBeVisible();
  await account.close();
}
