package dev.shop.api.payment;

import dev.shop.api.order.RefundRequested;
import dev.shop.api.payment.PaymentRepository.RefundTarget;
import dev.shop.api.web.ListenerFailedException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/** Gives the money back at the provider, outside any transaction, then records it. A failure is retried later. */
@Component
class RefundProcessor {

    private final PaymentRefunds refunds;
    private final PaymentProvider provider;

    RefundProcessor(PaymentRefunds refunds, PaymentProvider provider) {
        this.refunds = refunds;
        this.provider = provider;
    }

    @ApplicationModuleListener(id = "payment.refund", propagation = Propagation.NOT_SUPPORTED)
    void on(RefundRequested event) {
        RefundTarget target = refunds.target(event.paymentId());
        try {
            provider.refund(target.providerPaymentId(), target.amountMinor(), target.currency(), event.paymentId());
        } catch (RuntimeException e) {
            throw new ListenerFailedException("Refund of order " + event.orderId() + " failed with "
                    + e.getClass().getSimpleName());
        }
        refunds.complete(event.orderId(), event.paymentId());
    }
}
