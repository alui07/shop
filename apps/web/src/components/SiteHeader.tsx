import { Link } from '@tanstack/react-router';

const NAV_LINK_CLASS = 'rounded-sm px-1 hover:underline [&.active]:font-semibold';

export function SiteHeader() {
  return (
    <header className="border-b">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
        <Link to="/" className="text-lg font-bold">
          Shop
        </Link>
        <nav aria-label="Main" className="flex gap-4 text-sm">
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
        </nav>
      </div>
    </header>
  );
}
