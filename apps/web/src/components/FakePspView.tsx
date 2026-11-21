import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { useCallback } from 'react';
import type { components } from '@/api/schema';
import { useHasPassed } from '@/hooks/useHasPassed';
import { ApiError } from '@/api/catalog';
import { completeFakePayment, fakePspPaymentQuery } from '@/api/payments';
import { ProblemError } from '@/api/problemError';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { formatDateTime } from '@/lib/dateTime';
import { failureMessage } from '@/lib/failureMessage';
import { RETURNED_FROM_PAYMENT } from '@/lib/paymentState';
import { orderIdFrom } from '@/lib/paymentUrls';
import { formatPrice } from '@/lib/price';
import { customerGate } from './customerGate';
import { LoadingMessage } from './LoadingMessage';
import { NotFoundPanel } from './NotFoundPanel';

const PAYMENT_STATE_TYPE = 'tag:shop.dev,2026:payment-state';
const EXPIRED_MESSAGE = 'This payment has expired';
const ALREADY_COMPLETED_MESSAGE = 'This payment is already completed.';
const COMPLETE_FAILED_MESSAGE = 'The payment could not be completed. Try again.';

function isPaymentStateConflict(error: Error): boolean {
  return (
    error instanceof ProblemError &&
    error.status === 409 &&
    error.problem.type === PAYMENT_STATE_TYPE
  );
}

function completeFailedText(error: Error): string {
  if (!(error instanceof ProblemError)) return COMPLETE_FAILED_MESSAGE;
  return failureMessage(error, COMPLETE_FAILED_MESSAGE);
}

export function FakePspView({ token }: { token: string }) {
  useDocumentTitle('Fake payment provider');
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { data: payment, error, isPending } = useQuery(fakePspPaymentQuery(token));

  const complete = useMutation({
    mutationFn: async (outcome: Outcome) => {
      let completed;
      try {
        completed = await completeFakePayment(token, outcome);
      } catch (error) {
        // A 409 means expired or already completed; the refreshed payment tells which.
        if (isPaymentStateConflict(error as Error)) {
          await queryClient
            .fetchQuery({ ...fakePspPaymentQuery(token), staleTime: 0 })
            .catch(() => undefined);
        }
        throw error;
      }
      const orderId = orderIdFrom(completed.returnUrl);
      if (orderId === null) throw new Error('Unexpected return address');
      return { orderId, outcome };
    },
    onSuccess: ({ orderId: id, outcome }) => {
      void queryClient.invalidateQueries({ queryKey: ['order', id] });
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
      return navigate({
        to: '/orders/$id',
        params: { id },
        state: outcome === 'SUCCEEDED' ? RETURNED_FROM_PAYMENT : undefined,
      });
    },
  });

  const focusWhenShown = useCallback(
    (element: HTMLParagraphElement | null) => element?.focus(),
    [],
  );

  const gate = customerGate(error);
  if (gate) return gate;
  if (error instanceof ApiError && error.status === 404) {
    return <NotFoundPanel title="Payment not found" message="This payment does not exist." />;
  }

  const choose = (outcome: Outcome) => () => {
    if (!complete.isPending) complete.mutate(outcome);
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Fake payment provider</h1>
      <p className="rounded-md border p-3 text-sm">Test page: no real payment is taken</p>
      {isPending && <LoadingMessage text="Loading payment" />}
      {error && <p role="alert">The payment could not be loaded.</p>}
      {payment && (
        <>
          <p className="text-lg font-semibold">Amount {formatPrice(payment)}</p>
          <p className="text-sm">Merchant reference {payment.merchantReference}</p>
          <p className="text-sm">
            Session ends{' '}
            <time dateTime={payment.expiresAt}>{formatDateTime(payment.expiresAt)}</time>
          </p>
          {payment.status === 'PENDING' ? (
            <PendingPayment
              payment={payment}
              error={complete.error}
              onChoose={choose}
              busy={complete.isPending}
              focusWhenShown={focusWhenShown}
            />
          ) : (
            <>
              <p role="status" tabIndex={-1} ref={complete.error ? focusWhenShown : undefined}>
                {ALREADY_COMPLETED_MESSAGE}
              </p>
              <OrderLink returnUrl={payment.returnUrl} />
            </>
          )}
        </>
      )}
    </div>
  );
}

type FakePspPayment = components['schemas']['FakePspPayment'];
type Outcome = 'SUCCEEDED' | 'DECLINED';

interface PendingPaymentProps {
  payment: FakePspPayment;
  error: Error | null;
  busy: boolean;
  onChoose: (outcome: Outcome) => () => void;
  focusWhenShown: (element: HTMLParagraphElement | null) => void;
}

/** The choices for an open session; once the session has ended, or the server says so, only the way back. */
function PendingPayment({ payment, error, busy, onChoose, focusWhenShown }: PendingPaymentProps) {
  const sessionEnded = useHasPassed(payment.expiresAt);
  const expired = sessionEnded || (error !== null && isPaymentStateConflict(error));
  const busyProps = {
    'aria-disabled': busy || undefined,
    className: 'aria-disabled:pointer-events-none aria-disabled:opacity-50',
  };

  if (expired) {
    return (
      <>
        <p role="alert" tabIndex={-1} ref={error ? focusWhenShown : undefined}>
          {EXPIRED_MESSAGE}
        </p>
        <OrderLink returnUrl={payment.returnUrl} />
      </>
    );
  }
  return (
    <>
      {error && (
        <p role="alert" tabIndex={-1} ref={focusWhenShown}>
          {completeFailedText(error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button {...busyProps} onClick={onChoose('SUCCEEDED')}>
          Pay
        </Button>
        <Button variant="outline" {...busyProps} onClick={onChoose('DECLINED')}>
          Decline
        </Button>
      </div>
    </>
  );
}

function OrderLink({ returnUrl }: { returnUrl: string }) {
  const orderId = orderIdFrom(returnUrl);
  if (orderId === null) return null;
  return (
    <Link to="/orders/$id" params={{ id: orderId }} className="underline">
      Back to order
    </Link>
  );
}
