import type { components } from '@/api/schema';
import { formatPrice } from '@/lib/price';

type AdminProduct = components['schemas']['AdminProduct'];

export function AdminProductTable({ products }: { products: readonly AdminProduct[] }) {
  return (
    <table className="w-full text-left text-sm">
      <caption className="sr-only">Products, including inactive ones</caption>
      <thead>
        <tr className="border-b">
          <th scope="col" className="py-2 pr-4">
            Name
          </th>
          <th scope="col" className="py-2 pr-4">
            Slug
          </th>
          <th scope="col" className="py-2 pr-4">
            Category
          </th>
          <th scope="col" className="py-2 pr-4">
            Price
          </th>
          <th scope="col" className="py-2">
            Status
          </th>
        </tr>
      </thead>
      <tbody>
        {products.map((product) => (
          <tr key={product.id} className="border-b">
            <th scope="row" className="py-2 pr-4 font-medium">
              {product.name}
            </th>
            <td className="py-2 pr-4">{product.slug}</td>
            <td className="py-2 pr-4">{product.category.name}</td>
            <td className="py-2 pr-4">{formatPrice(product.price)}</td>
            <td className="py-2">
              <span
                className={
                  product.active
                    ? 'rounded-full bg-secondary px-2 py-0.5 text-xs font-medium'
                    : 'rounded-full border px-2 py-0.5 text-xs text-muted-foreground'
                }
              >
                {product.active ? 'Active' : 'Inactive'}
              </span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
