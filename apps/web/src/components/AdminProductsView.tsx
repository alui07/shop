import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { adminProductsQuery } from '@/api/admin';
import { ApiError } from '@/api/catalog';
import { buttonVariants } from '@/components/ui/button';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import type { AdminSearch } from '@/lib/adminSearch';
import { AdminProductTable } from './AdminProductTable';
import { LoadingMessage } from './LoadingMessage';
import { NoAccessPanel } from './NoAccessPanel';
import { Pagination } from './Pagination';
import { SearchForm } from './SearchForm';
import { SignInPrompt } from './SignInPrompt';

export function AdminProductsView({
  search,
  onSearch,
}: {
  search: AdminSearch;
  onSearch: (query: string) => void;
}) {
  useDocumentTitle('Admin products');
  const { data, error, isPending } = useQuery(adminProductsQuery(search));

  if (error instanceof ApiError && error.status === 401) return <SignInPrompt />;
  if (error instanceof ApiError && error.status === 403) return <NoAccessPanel />;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold">Admin products</h1>
        <Link to="/admin/products/new" className={buttonVariants()}>
          New product
        </Link>
      </div>
      <SearchForm key={search.q ?? ''} initialQuery={search.q ?? ''} onSearch={onSearch} />
      {isPending && <LoadingMessage text="Loading products" />}
      {error && <p role="alert">The products could not be loaded.</p>}
      {data && data.totalItems === 0 && <p>No products match</p>}
      {data && data.totalItems > 0 && (
        <>
          <AdminProductTable products={data.items} />
          {data.totalPages > 1 && (
            <Pagination to="/admin" page={data.page + 1} totalPages={data.totalPages} />
          )}
        </>
      )}
    </div>
  );
}
