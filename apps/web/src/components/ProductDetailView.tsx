import { Link } from '@tanstack/react-router';
import type { components } from '@/api/schema';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { formatPrice } from '@/lib/price';
import { CategoryBadge } from './CategoryBadge';
import { ImagePlaceholder } from './ImagePlaceholder';

export function ProductDetailView({
  product,
}: {
  product: components['schemas']['ProductDetail'];
}) {
  useDocumentTitle(product.name);

  return (
    <article className="space-y-6">
      <Link to="/" className="text-sm underline">
        Back to products
      </Link>
      <div className="grid gap-6 md:grid-cols-2">
        <ImagePlaceholder className="aspect-4/3" />
        <div className="space-y-4">
          <h1 className="text-2xl font-semibold">{product.name}</h1>
          <CategoryBadge name={product.category.name} />
          <p className="text-xl font-semibold">{formatPrice(product.price)}</p>
          <p>{product.description}</p>
        </div>
      </div>
    </article>
  );
}
