import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { meQuery } from '@/api/session';
import type { components } from '@/api/schema';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { formatPrice } from '@/lib/price';
import { AddToCart } from './AddToCart';
import { CategoryBadge } from './CategoryBadge';
import { ImagePlaceholder } from './ImagePlaceholder';
import { SignInLink } from './SignInLink';

export function ProductDetailView({
  product,
}: {
  product: components['schemas']['ProductDetail'];
}) {
  useDocumentTitle(product.name);
  const { data: me } = useQuery(meQuery);

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
          {me?.authenticated === false && (
            <SignInLink className="inline-flex h-9 items-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground">
              Sign in to buy
            </SignInLink>
          )}
          {me?.roles.includes('customer') && <AddToCart slug={product.slug} />}
        </div>
      </div>
    </article>
  );
}
