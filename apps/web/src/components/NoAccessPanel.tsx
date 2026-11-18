import { useDocumentTitle } from '@/hooks/useDocumentTitle';

export function NoAccessPanel() {
  useDocumentTitle('No access');
  return (
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">You do not have access</h1>
      <p>This area is only for shop administrators.</p>
    </section>
  );
}
