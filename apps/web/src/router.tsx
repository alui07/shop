import type { QueryClient } from '@tanstack/react-query';
import { createRouter, type RouterHistory } from '@tanstack/react-router';
import { ErrorPanel } from '@/components/ErrorPanel';
import { NotFoundPanel } from '@/components/NotFoundPanel';
import { parseSearch, stringifySearch } from '@/lib/searchParams';
import { routeTree } from './routeTree.gen';

export function createAppRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({
    routeTree,
    history,
    context: { queryClient },
    parseSearch,
    stringifySearch,
    defaultPreload: 'intent',
    defaultPreloadStaleTime: 0,
    defaultPendingMs: 300,
    defaultPendingMinMs: 300,
    defaultErrorComponent: ErrorPanel,
    defaultNotFoundComponent: () => (
      <NotFoundPanel title="Page not found" message="There is nothing at this address." />
    ),
  });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
