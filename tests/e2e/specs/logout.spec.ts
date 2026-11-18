import { fetchMe, signIn, signInLink, signOutButton } from './support/auth';
import { expect, frontDoorOrigin, idpOrigin, test } from './support/test';

// The e2e compose stack sets the realm's access token lifespan to this many seconds (the ID token
// follows it). The cluster realm keeps the default 300 s, which is too long to wait for here.
const shortLifespanSeconds = Number(process.env.E2E_ACCESS_TOKEN_LIFESPAN ?? 0);

test('sign-out works with an expired id_token_hint (C23)', async ({ page }) => {
  test.skip(shortLifespanSeconds <= 0, 'needs E2E_ACCESS_TOKEN_LIFESPAN (compose e2e only)');
  await signIn(page, 'customer');

  await page.waitForTimeout((shortLifespanSeconds + 3) * 1000);
  await signOutButton(page).click();

  // No Keycloak confirmation page: the expired hint was accepted and the redirect came back.
  await expect(signInLink(page)).toBeVisible();
  expect(new URL(page.url()).origin).toBe(frontDoorOrigin);
  expect((await fetchMe(page)).authenticated).toBe(false);
});

test('an IdP-initiated logout ends the app session through back-channel logout (C33)', async ({
  page,
}) => {
  await signIn(page, 'customer');
  expect((await fetchMe(page)).authenticated).toBe(true);

  const idpPage = await page.context().newPage();
  await idpPage.goto(`${idpOrigin}/realms/shop/protocol/openid-connect/logout`);
  await idpPage.getByRole('button', { name: /log ?out/i }).click();
  await expect(idpPage.getByRole('heading', { name: /logged out/i })).toBeVisible();

  await expect
    .poll(async () => (await fetchMe(page)).authenticated, { timeout: 15_000 })
    .toBe(false);
});
