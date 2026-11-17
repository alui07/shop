import { useSuspenseQuery } from '@tanstack/react-query';
import { createFileRoute, notFound } from '@tanstack/react-router';
import { ApiError, productQuery } from '@/api/catalog';
import { LoadingMessage } from '@/components/LoadingMessage';
import { NotFoundPanel } from '@/components/NotFoundPanel';
import { ProductDetailView } from '@/components/ProductDetailView';

export const Route = createFileRoute('/products/$slug')({
  loader: async ({ context: { queryClient }, params: { slug } }) => {
    try {
      await queryClient.ensureQueryData(productQuery(slug));
    } catch (error) {
      if (error instanceof ApiError && (error.status === 404 || error.status === 400)) {
        throw notFound();
      }
      throw error;
    }
  },
  component: ProductPage,
  pendingComponent: () => <LoadingMessage text="Loading product" />,
  notFoundComponent: () => (
    <NotFoundPanel
      title="Product not found"
      message="This product does not exist or is no longer available."
    />
  ),
});

function ProductPage() {
  const { slug } = Route.useParams();
  const { data } = useSuspenseQuery(productQuery(slug));
  return <ProductDetailView product={data} />;
}
