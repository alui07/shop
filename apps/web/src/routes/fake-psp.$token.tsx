import { createFileRoute } from '@tanstack/react-router';
import { FakePspView } from '@/components/FakePspView';

export const Route = createFileRoute('/fake-psp/$token')({ component: FakePspPage });

function FakePspPage() {
  return <FakePspView token={Route.useParams().token} />;
}
