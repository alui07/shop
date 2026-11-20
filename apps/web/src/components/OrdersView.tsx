import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { ordersQuery } from '@/api/orders';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { formatDateTime } from '@/lib/dateTime';
import { STATUS_LABELS } from '@/lib/orderStatus';
import { formatPrice } from '@/lib/price';
import { customerGate } from './customerGate';
import { LoadingMessage } from './LoadingMessage';
import { Pagination } from './Pagination';

export function OrdersView({ page }: { page: number | undefined }) {
  useDocumentTitle('Your orders');
  const { data, error, isPending } = useQuery(ordersQuery(page));

  const gate = customerGate(error);
  if (gate) return gate;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Your orders</h1>
      {isPending && <LoadingMessage text="Loading orders" />}
      {error && <p role="alert">Your orders could not be loaded.</p>}
      {data && data.totalItems === 0 && <p>You have no orders yet</p>}
      {data && data.totalItems > 0 && (
        <>
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Your orders, newest first</caption>
            <thead>
              <tr className="border-b">
                <th scope="col" className="py-2 pr-4">
                  Placed
                </th>
                <th scope="col" className="py-2 pr-4">
                  Status
                </th>
                <th scope="col" className="py-2 pr-4">
                  Items
                </th>
                <th scope="col" className="py-2 pr-4">
                  Total
                </th>
                <th scope="col" className="py-2">
                  Details
                </th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((order) => (
                <tr key={order.id} className="border-b">
                  <td className="py-2 pr-4">
                    <time dateTime={order.createdAt}>{formatDateTime(order.createdAt)}</time>
                  </td>
                  <td className="py-2 pr-4">{STATUS_LABELS[order.status]}</td>
                  <td className="py-2 pr-4">{order.itemCount}</td>
                  <td className="py-2 pr-4">{formatPrice(order.total)}</td>
                  <td className="py-2">
                    <Link to="/orders/$id" params={{ id: order.id }} className="underline">
                      View order {formatDateTime(order.createdAt)}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.totalPages > 1 && (
            <Pagination to="/orders" page={data.page + 1} totalPages={data.totalPages} />
          )}
        </>
      )}
    </div>
  );
}
