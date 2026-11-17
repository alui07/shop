import { useRouter } from '@tanstack/react-router';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';

export function ErrorPanel() {
  const router = useRouter();
  useDocumentTitle('Something went wrong');

  return (
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">Something went wrong</h1>
      <p role="alert">The shop could not load this page.</p>
      <Button onClick={() => void router.invalidate()}>Try again</Button>
    </section>
  );
}
