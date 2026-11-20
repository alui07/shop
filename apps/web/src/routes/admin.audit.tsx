import { createFileRoute } from '@tanstack/react-router';
import { AuditView } from '@/components/AuditView';
import { parseAuditSearch } from '@/lib/adminSearch';

export const Route = createFileRoute('/admin/audit')({
  validateSearch: parseAuditSearch,
  component: AuditPage,
});

function AuditPage() {
  return <AuditView page={Route.useSearch().page} />;
}
