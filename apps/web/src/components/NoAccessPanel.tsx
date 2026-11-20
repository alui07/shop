import { useDocumentTitle } from '@/hooks/useDocumentTitle';

export function NoAccessPanel({
  message = 'This area is only for shop administrators.',
}: {
  message?: string;
}) {
  useDocumentTitle('No access');
  return (
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">You do not have access</h1>
      <p>{message}</p>
    </section>
  );
}
