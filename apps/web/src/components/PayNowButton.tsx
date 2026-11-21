import { useMutation } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { startPayment } from '@/api/payments';
import { ProblemError } from '@/api/problemError';
import { Button } from '@/components/ui/button';
import { failureMessage } from '@/lib/failureMessage';
import { paymentTokenFrom } from '@/lib/paymentUrls';

const NOT_PAYABLE_MESSAGE = 'This order can no longer be paid.';
const START_FAILED_MESSAGE = 'The payment could not be started. Try again.';

function startFailedText(error: Error): string {
  if (!(error instanceof ProblemError)) return START_FAILED_MESSAGE;
  return error.status === 409 ? NOT_PAYABLE_MESSAGE : failureMessage(error, START_FAILED_MESSAGE);
}

export function PayNowButton({ orderId }: { orderId: string }) {
  const navigate = useNavigate();
  const start = useMutation({
    mutationFn: async () => {
      const { redirectUrl } = await startPayment(orderId);
      const token = paymentTokenFrom(redirectUrl);
      if (token === null) throw new Error('Unexpected payment page address');
      return token;
    },
    onSuccess: (token) => navigate({ to: '/fake-psp/$token', params: { token } }),
  });

  return (
    <>
      {start.error && <p role="alert">{startFailedText(start.error)}</p>}
      <Button
        aria-disabled={start.isPending || undefined}
        className="aria-disabled:pointer-events-none aria-disabled:opacity-50"
        onClick={() => {
          if (!start.isPending) start.mutate();
        }}
      >
        Pay now
      </Button>
    </>
  );
}
