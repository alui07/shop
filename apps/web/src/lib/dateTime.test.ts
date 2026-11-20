import { expect, it } from 'vitest';
import { formatDateTime } from './dateTime';

it('formats an instant in UTC', () => {
  expect(formatDateTime('2026-11-19T10:15:00Z')).toBe('Nov 19, 2026, 10:15:00 AM UTC');
});
