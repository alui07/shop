import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { meQuery } from '@/api/session';
import { CartLink } from './CartLink';
import { SignInLink } from './SignInLink';
import { UserMenu } from './UserMenu';

const NAV_LINK_CLASS = 'rounded-sm px-1 hover:underline [&.active]:font-semibold';

export function SiteHeader() {
  const { data: me, isPending } = useQuery(meQuery);

  return (
    <header className="border-b">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
        <Link to="/" className="text-lg font-bold">
          Shop
        </Link>
        <nav aria-label="Main" className="flex items-center gap-4 text-sm">
          <Link
            to="/"
            className={NAV_LINK_CLASS}
            activeOptions={{ exact: true, includeSearch: false }}
          >
            Products
          </Link>
          <Link to="/status" className={NAV_LINK_CLASS}>
            Status
          </Link>
          {me?.roles.includes('customer') && <CartLink className={NAV_LINK_CLASS} />}
          {isPending ? null : me?.authenticated ? (
            <UserMenu me={me} />
          ) : (
            <SignInLink className={NAV_LINK_CLASS} />
          )}
        </nav>
      </div>
    </header>
  );
}
