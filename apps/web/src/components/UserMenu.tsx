import { useQueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useState } from 'react';
import type { components } from '@/api/schema';
import { meQuery } from '@/api/session';
import { Button } from '@/components/ui/button';
import { signOut } from '@/lib/signOut';

type Me = components['schemas']['Me'];

export function UserMenu({ me }: { me: Me }) {
  const queryClient = useQueryClient();
  const [failed, setFailed] = useState(false);

  const handleSignOut = async () => {
    setFailed(false);
    if (!(await signOut())) {
      setFailed(true);
      await queryClient.invalidateQueries({ queryKey: meQuery.queryKey });
    }
  };

  return (
    <div className="flex items-center gap-3">
      <span data-testid="user-name">{me.name ?? me.email ?? 'Signed in'}</span>
      {me.roles.includes('admin') && (
        <Link to="/admin" className="rounded-sm px-1 hover:underline [&.active]:font-semibold">
          Admin
        </Link>
      )}
      <Button variant="outline" size="sm" onClick={() => void handleSignOut()}>
        Sign out
      </Button>
      {failed && <span role="alert">Sign out failed. Try again.</span>}
    </div>
  );
}
