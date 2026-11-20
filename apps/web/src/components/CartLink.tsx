import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { cartQuery } from '@/api/cart';

export function CartLink({ className }: { className: string }) {
  const { data: cart } = useQuery(cartQuery);

  return (
    <Link to="/cart" className={className}>
      Cart{cart && ` (${cart.itemCount})`}
    </Link>
  );
}
