import type { components } from '@/api/schema';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { ProductForm } from './ProductForm';

export function AdminProductCreateView({
  onCreated,
}: {
  onCreated: (product: components['schemas']['AdminProduct']) => void;
}) {
  useDocumentTitle('New product');
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">New product</h1>
      <ProductForm mode="create" onCreated={onCreated} />
    </div>
  );
}
