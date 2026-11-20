import { createFileRoute } from '@tanstack/react-router';
import { OrdersView } from '@/components/OrdersView';
import { parseOrdersSearch } from '@/lib/ordersSearch';

export const Route = createFileRoute('/orders/')({
  validateSearch: parseOrdersSearch,
  component: OrdersPage,
});

function OrdersPage() {
  return <OrdersView page={Route.useSearch().page} />;
}
