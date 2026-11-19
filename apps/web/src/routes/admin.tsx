import { useSuspenseQuery } from '@tanstack/react-query';
import { createFileRoute, Link, Outlet } from '@tanstack/react-router';
import { meQuery } from '@/api/session';
import { LoadingMessage } from '@/components/LoadingMessage';
import { SignInPrompt } from '@/components/SignInPrompt';
import { NoAccessPanel } from '@/components/NoAccessPanel';

const NAV_LINK_CLASS = 'rounded-sm px-1 hover:underline [&.active]:font-semibold';

export const Route = createFileRoute('/admin')({
  beforeLoad: async ({ context: { queryClient } }) => {
    await queryClient.ensureQueryData(meQuery);
  },
  component: AdminLayout,
  pendingComponent: () => <LoadingMessage text="Loading" />,
});

/** The API enforces access on every admin call; this only decides which screen to show. */
function AdminLayout() {
  const { data: me } = useSuspenseQuery(meQuery);
  if (!me.authenticated) return <SignInPrompt />;
  if (!me.roles.includes('admin')) return <NoAccessPanel />;
  return (
    <div className="space-y-6">
      <nav aria-label="Admin" className="flex gap-4 text-sm">
        <Link
          to="/admin"
          className={NAV_LINK_CLASS}
          activeOptions={{ exact: true, includeSearch: false }}
        >
          Products
        </Link>
        <Link to="/admin/audit" className={NAV_LINK_CLASS}>
          Audit log
        </Link>
      </nav>
      <Outlet />
    </div>
  );
}
