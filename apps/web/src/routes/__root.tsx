import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';
import { LoginFailedAlert } from '@/components/LoginFailedAlert';
import { SiteFooter } from '@/components/SiteFooter';
import { SiteHeader } from '@/components/SiteHeader';
import { useReturnToRestore } from '@/hooks/useReturnToRestore';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: RootLayout,
});

function RootLayout() {
  useReturnToRestore();
  return (
    <div className="flex min-h-screen flex-col">
      <SiteHeader />
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        <LoginFailedAlert />
        <Outlet />
      </main>
      <SiteFooter />
    </div>
  );
}
