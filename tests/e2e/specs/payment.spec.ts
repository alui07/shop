import type { Browser, BrowserContext, Page } from '@playwright/test';
import { apiFetch, fetchMe, signIn, signOut, signOutButton } from './support/auth';
import { deactivateProduct } from './support/admin';
import {
  expectExactlyOneOrderMail,
  expectNoOrderMail,
  searchMail,
  orderMailPrefix,
} from './support/mailpit';
import { registerAndVerify, openAccountEmailForm, signInWithAccount } from './support/registration';
import { resetShopper } from './support/shopper';
import { createStockedProduct, uniqueWombat } from './support/stockedProduct';
import { expect, test } from './support/test';

// Runs in its own project after checkout (see playwright.config.ts): it buys as customer and as a
// freshly registered user, and pays on the fake provider page. It buys only a product it creates.

test.describe.configure({ mode: 'serial' });

const product = uniqueWombat();
const customerEmail = 'customer@example.test';
let productCreated = false;
let customerContext: BrowserContext;
let adminContext: BrowserContext;
let customer: Page;
let admin: Page;

async function openSignedInPage(browser: Browser, role: 'customer' | 'admin') {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, role);
  return { context, page };
}

async function addOneAndPlaceOrder(page: Page): Promise<string> {
  await page.goto(`/products/${product.slug}`);
  await expect(page.getByRole('heading', { level: 1, name: product.name })).toBeVisible();
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByText('Added to cart')).toBeVisible();
  await page.getByRole('link', { name: /^Cart/ }).click();
  await page.getByRole('button', { name: 'Checkout' }).click();
  await page.getByRole('button', { name: 'Place order' }).click();
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  return orderIdOf(page);
}

function orderIdOf(page: Page): string {
  const match = /\/orders\/([0-9a-f-]{36})$/.exec(page.url());
  expect(match, page.url()).not.toBeNull();
  return (match as RegExpExecArray)[1] as string;
}

async function payNow(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Pay now' }).click();
  await expect(page).toHaveURL(/\/fake-psp\/[^/]+$/);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Fake payment provider' }),
  ).toBeVisible();
  await expect(page.getByText('Test page: no real payment is taken')).toBeVisible();
}

async function orderOf(page: Page, orderId: string) {
  const response = await apiFetch(page, `/api/orders/${orderId}`);
  expect(response.status).toBe(200);
  return JSON.parse(response.body) as { status: string; paymentStatus: string | null };
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120_000);
  ({ context: customerContext, page: customer } = await openSignedInPage(browser, 'customer'));
  ({ context: adminContext, page: admin } = await openSignedInPage(browser, 'admin'));
  await resetShopper(customer, 'customer');
  await createStockedProduct(admin, product, 6);
  productCreated = true;
});

test.afterAll(async () => {
  // Cleanup failures must not hide a test failure; the next run's admin-cleanup deactivates leftovers.
  await resetShopper(customer, 'customer').catch((error: unknown) => {
    console.error('reset of customer failed', error);
  });
  if (productCreated) {
    await deactivateProduct(admin, product.slug).catch((error: unknown) => {
      console.error(`cleanup of ${product.slug} failed`, error);
    });
  }
  await customerContext?.close();
  await adminContext?.close();
});

test('paying on the fake provider page marks the order paid and sends one confirmation email', async () => {
  const orderId = await addOneAndPlaceOrder(customer);
  await expect(customer.getByText('Awaiting payment', { exact: true })).toBeVisible();

  await payNow(customer);
  await customer.getByRole('button', { name: 'Pay', exact: true }).click();

  await expect(customer).toHaveURL(new RegExp(`/orders/${orderId}$`));
  await expect(customer.getByText('Paid', { exact: true })).toBeVisible({ timeout: 30_000 });
  await expect(customer.getByRole('button', { name: 'Pay now' })).toHaveCount(0);
  await expect(customer.getByRole('button', { name: 'Cancel order' })).toHaveCount(0);

  await expectExactlyOneOrderMail(orderId, customerEmail);

  // A reload drops the router state and shows the stored status.
  await customer.reload();
  await expect(customer.getByText('Paid', { exact: true })).toBeVisible();
});

test('declining keeps the order awaiting payment, sends no email and allows cancelling', async () => {
  const orderId = await addOneAndPlaceOrder(customer);

  await payNow(customer);
  await customer.getByRole('button', { name: 'Decline' }).click();
  await expect(customer).toHaveURL(new RegExp(`/orders/${orderId}$`));

  await expect
    .poll(async () => (await orderOf(customer, orderId)).paymentStatus, { timeout: 30_000 })
    .toBe('FAILED');
  expect((await orderOf(customer, orderId)).status).toBe('PENDING_PAYMENT');

  await customer.reload();
  await expect(customer.getByText('Awaiting payment', { exact: true })).toBeVisible();
  await expect(customer.getByRole('button', { name: 'Pay now' })).toBeVisible();
  await expectNoOrderMail(orderId, customerEmail);

  await customer.getByRole('button', { name: 'Cancel order' }).click();
  await expect(customer.getByText('Cancelled', { exact: true })).toBeVisible();
});

test('an unsigned or forged webhook is refused at the front door without a cookie', async ({
  request,
}) => {
  const event = {
    id: 'evt_e2e_forged',
    type: 'payment.succeeded',
    created: Math.floor(Date.now() / 1000),
    data: {
      providerPaymentId: 'pay_forged',
      merchantReference: crypto.randomUUID(),
      amountMinor: 1,
      currency: 'EUR',
    },
  };
  const forgedSignature = `t=${event.created},v1=${'0'.repeat(64)}`;
  const attempts: Record<string, string>[] = [{}, { 'Shop-Signature': forgedSignature }];
  for (const headers of attempts) {
    const response = await request.post('/api/payments/webhook', { data: event, headers });
    expect(response.status(), JSON.stringify(headers)).toBe(401);
    expect(response.headers()['set-cookie']).toBeUndefined();
  }
});

test('a registered shopper pays; a later address change does not redirect the email to an unverified address', async ({
  browser,
}) => {
  test.setTimeout(120_000);
  const shopper = await registerAndVerify(browser);
  try {
    const me = await fetchMe(shopper.page);
    expect(me.email).toBe(shopper.email);
    const orderId = await addOneAndPlaceOrder(shopper.page);

    const newAddress = `changed-${shopper.email}`;
    await openAccountEmailForm(shopper.page, newAddress);

    await payNow(shopper.page);
    await shopper.page.getByRole('button', { name: 'Pay', exact: true }).click();
    await expect(shopper.page.getByText('Paid', { exact: true })).toBeVisible({ timeout: 30_000 });

    await expectExactlyOneOrderMail(orderId, shopper.email);
    const toNewAddress = (await searchMail(orderMailPrefix(orderId))).filter((message) =>
      message.recipients.includes(newAddress.toLowerCase()),
    );
    expect(toNewAddress, 'confirmation sent to the unverified new address').toHaveLength(0);

    // R51: Keycloak resets email-verified on the change, so the next sign-in requires verification and gives no shop session.
    await signOut(shopper.page);
    await signInWithAccount(shopper.page, shopper);
    await expect(shopper.page.getByRole('heading', { name: 'Email verification' })).toBeVisible();
    await expect(signOutButton(shopper.page)).toHaveCount(0);
    const anyToNewAddress = await searchMail(`to:"${newAddress}"`);
    expect(
      anyToNewAddress.filter((message) => message.subject.includes('confirmed')),
      'order mail at the unverified new address',
    ).toHaveLength(0);
  } finally {
    await resetShopper(shopper.page, 'customer').catch(() => undefined);
    await shopper.context.close();
  }
});
