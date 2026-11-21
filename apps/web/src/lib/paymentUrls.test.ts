import { describe, expect, it } from 'vitest';
import { orderIdFrom, paymentTokenFrom } from './paymentUrls';

const ID = '11111111-1111-4111-8111-111111111111';

describe('paymentTokenFrom', () => {
  it.each([
    ['/fake-psp/abc_DEF-123', 'abc_DEF-123'],
    [`${window.location.origin}/fake-psp/abc`, 'abc'],
  ])('accepts %s', (value, token) => {
    expect(paymentTokenFrom(value)).toBe(token);
  });

  it.each([
    'https://evil.example/fake-psp/abc',
    '//evil.example/fake-psp/abc',
    'javascript:alert(1)',
    'http://[',
    '/fake-psp/abc?next=1',
    '/fake-psp/abc#frag',
    '/fake-psp/a/b',
    '/fake-psp/',
    `/orders/${ID}`,
    `/fake-psp/${'a'.repeat(2000)}`,
  ])('refuses %s', (value) => {
    expect(paymentTokenFrom(value)).toBeNull();
  });
});

describe('orderIdFrom', () => {
  it.each([`/orders/${ID}`, `${window.location.origin}/orders/${ID}`])('accepts %s', (value) => {
    expect(orderIdFrom(value)).toBe(ID);
  });

  it.each([
    `https://evil.example/orders/${ID}`,
    `//evil.example/orders/${ID}`,
    'javascript:alert(1)',
    '/orders/not-a-uuid',
    `/orders/${ID}/extra`,
  ])('refuses %s', (value) => {
    expect(orderIdFrom(value)).toBeNull();
  });
});
