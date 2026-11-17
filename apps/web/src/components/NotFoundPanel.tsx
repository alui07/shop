import { Link } from '@tanstack/react-router';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';

export function NotFoundPanel({ title, message }: { title: string; message: string }) {
  useDocumentTitle(title);

  return (
    <section className="space-y-4">
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p>{message}</p>
      <Link to="/" className="underline">
        Back to products
      </Link>
    </section>
  );
}
