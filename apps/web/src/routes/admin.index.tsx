import { createFileRoute } from '@tanstack/react-router';
import { AdminProductsView } from '@/components/AdminProductsView';
import { parseAdminSearch } from '@/lib/adminSearch';

export const Route = createFileRoute('/admin/')({
  validateSearch: parseAdminSearch,
  component: AdminProductsPage,
});

function AdminProductsPage() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  return (
    <AdminProductsView
      search={search}
      onSearch={(q) => void navigate({ search: { q: q === '' ? undefined : q } })}
    />
  );
}
