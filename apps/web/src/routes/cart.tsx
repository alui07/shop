import { createFileRoute } from '@tanstack/react-router';
import { CartView } from '@/components/CartView';

export const Route = createFileRoute('/cart')({ component: CartView });
