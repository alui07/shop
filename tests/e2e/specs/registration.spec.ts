import { fetchMe, signOutButton } from './support/auth';
import { searchMail } from './support/mailpit';
import {
  newAccount,
  registerAndVerify,
  signInWithAccount,
  submitRegistration,
  verifyAndSetPassword,
} from './support/registration';
import { expect, idpOrigin, test } from './support/test';

// Each test registers its own address, so a second run on the same Keycloak and Mailpit passes.

test('a new customer registers, verifies the address, sets a password and is signed in', async ({
  browser,
}) => {
  const account = newAccount();
  const registering = await browser.newContext();
  const registeringPage = await registering.newPage();
  await submitRegistration(registeringPage, account);
  await expect(registeringPage.getByRole('heading', { name: 'Email verification' })).toBeVisible();

  // Before the link is followed there is no password, so a separate browser cannot sign in.
  const unverified = await browser.newContext();
  const unverifiedPage = await unverified.newPage();
  await signInWithAccount(unverifiedPage, account);
  await expect(unverifiedPage).toHaveURL(new RegExp(`^${idpOrigin}/realms/shop/`));
  await expect(signOutButton(unverifiedPage)).toHaveCount(0);
  await unverified.close();

  await verifyAndSetPassword(registeringPage, account);
  const me = await fetchMe(registeringPage);
  expect(me.email).toBe(account.email);
  expect(me.roles).toContain('customer');
  expect(me.roles).not.toContain('admin');
  await registering.close();

  const context = await browser.newContext();
  const page = await context.newPage();
  await signInWithAccount(page, account);
  await expect(signOutButton(page)).toBeVisible();
  await context.close();
});

test('a wrong password for a registered address is refused', async ({ browser }) => {
  const shopper = await registerAndVerify(browser);
  const other = await browser.newContext();
  const otherPage = await other.newPage();
  await signInWithAccount(otherPage, { ...shopper, password: `Wrong-${crypto.randomUUID()}` });
  await expect(otherPage.getByText(/invalid/i).first()).toBeVisible();
  await expect(signOutButton(otherPage)).toHaveCount(0);
  await shopper.context.close();
  await other.close();
});

test('registering an address that is already taken is refused without a second mail', async ({
  browser,
}) => {
  const account = newAccount();
  const first = await browser.newContext();
  await submitRegistration(await first.newPage(), account);
  await expect(async () => {
    expect(await searchMail(`to:"${account.email}"`)).toHaveLength(1);
  }).toPass({ timeout: 20_000 });

  const second = await browser.newContext();
  const secondPage = await second.newPage();
  await submitRegistration(secondPage, account);
  await expect(secondPage.getByText(/already exists|in use/i).first()).toBeVisible();
  // Keycloak sends mail inside the request that refused the registration, so the refusal page is the marker.
  expect(await searchMail(`to:"${account.email}"`)).toHaveLength(1);
  await first.close();
  await second.close();
});
