const DATE_TIME_FORMAT = new Intl.DateTimeFormat('en', {
  dateStyle: 'medium',
  timeStyle: 'long',
  timeZone: 'UTC',
});

export function formatDateTime(iso: string): string {
  return DATE_TIME_FORMAT.format(new Date(iso));
}
