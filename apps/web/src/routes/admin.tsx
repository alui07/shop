import { useSuspenseQuery } from '@tanstack/react-query';
import { createFileRoute, Outlet } from '@tanstack/react-router';
import { meQuery } from '@/api/session';
import { LoadingMessage } from '@/components/LoadingMessage';
import { SignInPrompt } from '@/components/SignInPrompt';
import { NoAccessPanel } from '@/components/NoAccessPanel';

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
  return <Outlet />;
}
