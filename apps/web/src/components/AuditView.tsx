import { useQuery } from '@tanstack/react-query';
import { auditQuery } from '@/api/admin';
import { ApiError } from '@/api/catalog';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { AuditTable } from './AuditTable';
import { LoadingMessage } from './LoadingMessage';
import { NoAccessPanel } from './NoAccessPanel';
import { Pagination } from './Pagination';
import { SignInPrompt } from './SignInPrompt';

export function AuditView({ page }: { page: number | undefined }) {
  useDocumentTitle('Audit log');
  const { data, error, isPending } = useQuery(auditQuery(page));

  if (error instanceof ApiError && error.status === 401) return <SignInPrompt />;
  if (error instanceof ApiError && error.status === 403) return <NoAccessPanel />;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Audit log</h1>
      {isPending && <LoadingMessage text="Loading audit log" />}
      {error && <p role="alert">The audit log could not be loaded.</p>}
      {data && data.totalItems === 0 && <p>No changes recorded yet</p>}
      {data && data.totalItems > 0 && (
        <>
          <AuditTable events={data.items} />
          {data.totalPages > 1 && (
            <Pagination to="/admin/audit" page={data.page + 1} totalPages={data.totalPages} />
          )}
        </>
      )}
    </div>
  );
}
