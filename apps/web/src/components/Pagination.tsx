import { Link } from '@tanstack/react-router';
import { pageWindow } from '@/lib/pageWindow';

const ITEM_CLASS =
  'inline-flex h-9 min-w-9 items-center justify-center rounded-md border px-3 text-sm';

type PagedRoute = '/' | '/admin' | '/admin/audit' | '/orders';

function PageLink({
  to,
  page,
  children,
}: {
  to: PagedRoute;
  page: number;
  children: React.ReactNode;
}) {
  return (
    <Link
      to={to}
      search={(previous: object) => ({ ...previous, page: page === 1 ? undefined : page })}
      className={`${ITEM_CLASS} hover:bg-accent`}
      activeOptions={{ exact: true }}
    >
      {children}
    </Link>
  );
}

function Disabled({ children }: { children: React.ReactNode }) {
  return (
    <span aria-disabled="true" className={`${ITEM_CLASS} text-muted-foreground`}>
      {children}
    </span>
  );
}

export function Pagination({
  to = '/',
  page,
  totalPages,
}: {
  to?: PagedRoute;
  page: number;
  totalPages: number;
}) {
  return (
    <nav aria-label="Pagination" className="flex flex-wrap items-center justify-center gap-2">
      {page > 1 ? (
        <PageLink to={to} page={page - 1}>
          Previous
        </PageLink>
      ) : (
        <Disabled>Previous</Disabled>
      )}
      {pageWindow(page, totalPages).map((number) =>
        number === page ? (
          <span
            key={number}
            aria-current="page"
            className={`${ITEM_CLASS} bg-primary text-primary-foreground`}
          >
            {number}
          </span>
        ) : (
          <PageLink key={number} to={to} page={number}>
            {number}
          </PageLink>
        ),
      )}
      {page < totalPages ? (
        <PageLink to={to} page={page + 1}>
          Next
        </PageLink>
      ) : (
        <Disabled>Next</Disabled>
      )}
    </nav>
  );
}
