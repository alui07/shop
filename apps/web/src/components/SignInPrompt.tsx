import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { SignInLink } from './SignInLink';

export function SignInPrompt() {
  useDocumentTitle('Sign in');
  return (
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">Sign in</h1>
      <p>You need to sign in to use the admin area.</p>
      <SignInLink className="inline-flex h-9 items-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground" />
    </section>
  );
}
