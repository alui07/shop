import { Link } from '@tanstack/react-router';
import { Card, CardContent } from '@/components/ui/card';
import { formatPrice } from '@/lib/price';
import { CategoryBadge } from './CategoryBadge';
import { ImagePlaceholder } from './ImagePlaceholder';
import type { components } from '@/api/schema';

export function ProductCard({ product }: { product: components['schemas']['ProductSummary'] }) {
  return (
    <article>
      <Card className="relative h-full gap-3 p-3 focus-within:ring-2 focus-within:ring-ring">
        <ImagePlaceholder className="aspect-4/3" />
        <CardContent className="space-y-2 px-0">
          <h2 className="font-medium">
            <Link
              to="/products/$slug"
              params={{ slug: product.slug }}
              className="after:absolute after:inset-0 focus-visible:outline-none"
            >
              {product.name}
            </Link>
          </h2>
          <CategoryBadge name={product.category.name} />
          <p className="font-semibold" data-testid="price">
            {formatPrice(product.price)}
          </p>
        </CardContent>
      </Card>
    </article>
  );
}
