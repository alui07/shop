import { useSuspenseQuery } from '@tanstack/react-query';
import { createFileRoute } from '@tanstack/react-router';
import { categoriesQuery, productsQuery } from '@/api/catalog';
import { CatalogFilters } from '@/components/CatalogFilters';
import { LoadingMessage } from '@/components/LoadingMessage';
import { ProductResults } from '@/components/ProductResults';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { parseCatalogSearch } from '@/lib/catalogSearch';

export const Route = createFileRoute('/')({
  validateSearch: parseCatalogSearch,
  loaderDeps: ({ search }) => search,
  loader: async ({ context: { queryClient }, deps }) => {
    await Promise.all([
      queryClient.ensureQueryData(categoriesQuery),
      queryClient.ensureQueryData(productsQuery(deps)),
    ]);
  },
  component: CatalogPage,
  pendingComponent: () => <LoadingMessage text="Loading products" />,
});

function CatalogPage() {
  useDocumentTitle('Products');
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  const { data: categories } = useSuspenseQuery(categoriesQuery);
  const { data: results } = useSuspenseQuery(productsQuery(search));

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Products</h1>
      <CatalogFilters
        search={search}
        categories={categories}
        onChange={(change) =>
          void navigate({ search: (previous) => ({ ...previous, ...change, page: undefined }) })
        }
      />
      <ProductResults results={results} />
    </div>
  );
}
