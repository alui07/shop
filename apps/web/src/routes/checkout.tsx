import { createFileRoute } from '@tanstack/react-router';
import { CheckoutView } from '@/components/CheckoutView';

export const Route = createFileRoute('/checkout')({ component: CheckoutView });
