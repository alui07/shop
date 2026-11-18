import { useLocation } from '@tanstack/react-router';

export function LoginFailedAlert() {
  const failed = useLocation({
    select: (location) => (location.search as Record<string, unknown>)['login'] === 'failed',
  });
  if (!failed) return null;
  return (
    <p role="alert" className="mb-4 rounded-md border border-destructive p-3 text-destructive">
      Sign-in failed. Please try again.
    </p>
  );
}
