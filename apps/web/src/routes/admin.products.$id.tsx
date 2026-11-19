import { createFileRoute } from '@tanstack/react-router';
import { useEffect } from 'react';
import { AdminProductEditView } from '@/components/AdminProductEditView';
import { NotFoundPanel } from '@/components/NotFoundPanel';
import { parseProductId } from '@/lib/productId';

export const Route = createFileRoute('/admin/products/$id')({
  validateSearch: (raw: Record<string, unknown>): { saved?: 'true' } =>
    raw['saved'] === 'true' ? { saved: 'true' } : {},
  component: EditProductPage,
});

function EditProductPage() {
  const { id: rawId } = Route.useParams();
  const { saved } = Route.useSearch();
  const navigate = Route.useNavigate();
  const id = parseProductId(rawId);

  useEffect(() => {
    if (saved) void navigate({ search: {}, replace: true });
  }, [saved, navigate]);

  if (id === undefined) {
    return <NotFoundPanel title="Product not found" message="This product does not exist." />;
  }
  return <AdminProductEditView id={id} justCreated={saved !== undefined} />;
}
