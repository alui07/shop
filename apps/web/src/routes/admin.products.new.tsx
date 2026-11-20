import { createFileRoute } from '@tanstack/react-router';
import { AdminProductCreateView } from '@/components/AdminProductCreateView';

export const Route = createFileRoute('/admin/products/new')({
  component: NewProductPage,
});

function NewProductPage() {
  const navigate = Route.useNavigate();
  return (
    <AdminProductCreateView
      onCreated={(product) =>
        void navigate({
          to: '/admin/products/$id',
          params: { id: String(product.id) },
          search: { saved: 'true' },
        })
      }
    />
  );
}
