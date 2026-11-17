import { Link } from '@tanstack/react-router';
import type { components } from '@/api/schema';
import { Pagination } from './Pagination';
import { ProductCard } from './ProductCard';

type ProductPage = components['schemas']['ProductPage'];

export function ProductResults({ results }: { results: ProductPage }) {
  if (results.totalItems === 0) {
    return <p>No products match</p>;
  }
  if (results.items.length === 0) {
    return (
      <p>
        No products on this page.{' '}
        <Link
          to="/"
          search={(previous) => ({ ...previous, page: undefined })}
          className="underline"
        >
          Back to first page
        </Link>
      </p>
    );
  }
  return (
    <div className="space-y-6">
      <ul className="grid list-none gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {results.items.map((product) => (
          <li key={product.slug}>
            <ProductCard product={product} />
          </li>
        ))}
      </ul>
      {results.totalPages > 1 && (
        <Pagination page={results.page + 1} totalPages={results.totalPages} />
      )}
    </div>
  );
}
