import { createFileRoute } from '@tanstack/react-router';
import { OrderView } from '@/components/OrderView';

export const Route = createFileRoute('/orders/$id')({ component: OrderPage });

function OrderPage() {
  return <OrderView id={Route.useParams().id} />;
}
