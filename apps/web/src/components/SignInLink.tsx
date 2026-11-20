import { saveReturnTo } from '@/lib/returnTo';

export const SIGN_IN_PATH = '/oauth2/authorization/keycloak';

/** A full navigation to the API, which starts the OIDC flow; a router link would not leave the SPA. */
export function SignInLink({
  className,
  children = 'Sign in',
}: {
  className?: string;
  children?: string;
}) {
  return (
    <a href={SIGN_IN_PATH} className={className} onClick={saveReturnTo}>
      {children}
    </a>
  );
}
