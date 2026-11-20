import { ApiError } from '@/api/catalog';
import { NoAccessPanel } from './NoAccessPanel';
import { SignInPrompt } from './SignInPrompt';

/** The panel for a 401 or 403 answer to a customer page, or null for any other error. */
export function customerGate(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    return <SignInPrompt message="You need to sign in to shop." />;
  }
  if (error instanceof ApiError && error.status === 403) {
    return <NoAccessPanel message="This area is only for customers." />;
  }
  return null;
}
