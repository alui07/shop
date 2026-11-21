import { expect } from './test';

export interface MailMessage {
  id: string;
  subject: string;
  recipients: string[];
  text: string;
}

interface SearchBody {
  messages: { ID: string; Subject: string; To: { Address: string }[] }[];
}

function mailpitUrl(): string {
  const url = process.env.E2E_MAILPIT_URL;
  if (!url) throw new Error('E2E_MAILPIT_URL must be set for the e2e runner');
  return url;
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${mailpitUrl()}${path}`);
  expect(response.status, path).toBe(200);
  return (await response.json()) as T;
}

// Mailpit keeps every message until it restarts, so callers search by something unique to their
// own run (an order id prefix, a fresh address) and never assume the box is empty.
export async function searchMail(query: string): Promise<MailMessage[]> {
  const found = await getJson<SearchBody>(
    `/api/v1/search?query=${encodeURIComponent(query)}&limit=50`,
  );
  return Promise.all(
    found.messages.map(async (summary) => {
      const full = await getJson<{ Text: string }>(`/api/v1/message/${summary.ID}`);
      return {
        id: summary.ID,
        subject: summary.Subject,
        recipients: summary.To.map((to) => to.Address.toLowerCase()),
        text: full.Text,
      };
    }),
  );
}

export async function waitForMail(
  query: string,
  accept: (message: MailMessage) => boolean,
  timeoutMs = 20_000,
): Promise<MailMessage[]> {
  let matches: MailMessage[] = [];
  await expect
    .poll(
      async () => {
        matches = (await searchMail(query)).filter(accept);
        return matches.length;
      },
      { timeout: timeoutMs, message: `mail for "${query}"` },
    )
    .toBeGreaterThan(0);
  return matches;
}

export const orderMailPrefix = (orderId: string): string => orderId.slice(0, 8);

export function isOrderConfirmationFor(orderId: string, recipient: string) {
  return (message: MailMessage): boolean =>
    message.subject.includes(orderMailPrefix(orderId)) &&
    message.recipients.includes(recipient.toLowerCase());
}

// The mail is sent asynchronously by the API's outbox and nothing observable marks "no further
// mail is coming", so a duplicate can only be ruled out by a bounded wait. This is the one place
// that does it; the window is several times the delay of a delivery in the e2e stack.
const duplicateMailWindowMs = 3_000;

async function waitForPossibleDuplicates(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, duplicateMailWindowMs));
}

export async function expectExactlyOneOrderMail(orderId: string, recipient: string): Promise<void> {
  const accept = isOrderConfirmationFor(orderId, recipient);
  await waitForMail(orderMailPrefix(orderId), accept);
  await waitForPossibleDuplicates();
  const all = (await searchMail(orderMailPrefix(orderId))).filter(accept);
  expect(all, 'confirmation emails for the order').toHaveLength(1);
  expect(all[0]?.text).toContain(orderId);
}

export async function expectNoOrderMail(orderId: string, recipient: string): Promise<void> {
  const found = (await searchMail(orderMailPrefix(orderId))).filter(
    isOrderConfirmationFor(orderId, recipient),
  );
  expect(found, 'confirmation emails for the order').toHaveLength(0);
}

export function linkIn(message: MailMessage, pattern: RegExp): string {
  const link = pattern.exec(message.text)?.[0];
  expect(link, `link ${pattern} in "${message.subject}"`).toBeTruthy();
  return link as string;
}
