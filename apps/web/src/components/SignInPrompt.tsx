import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { SignInLink } from './SignInLink';

export function SignInPrompt({
  message = 'You need to sign in to use the admin area.',
}: {
  message?: string;
}) {
  useDocumentTitle('Sign in');
  return (
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">Sign in</h1>
      <p>{message}</p>
      <SignInLink className="inline-flex h-9 items-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground" />
    </section>
  );
}
