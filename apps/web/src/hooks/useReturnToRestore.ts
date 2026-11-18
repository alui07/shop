import { useQuery } from '@tanstack/react-query';
import { useLocation, useRouter } from '@tanstack/react-router';
import { useEffect } from 'react';
import { meQuery } from '@/api/session';
import { takeReturnTo } from '@/lib/returnTo';

/** The API sends every finished sign-in back to `/`; this puts the visitor back where they clicked "Sign in". */
export function useReturnToRestore(): void {
  const router = useRouter();
  const pathname = useLocation({ select: (location) => location.pathname });
  const { data: me } = useQuery(meQuery);

  useEffect(() => {
    if (pathname !== '/' || me === undefined) return;
    const target = takeReturnTo(me.authenticated);
    if (target !== null && target !== '/') router.history.replace(target);
  }, [pathname, me, router]);
}
