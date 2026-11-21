package dev.shop.api.payment;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records an event no payment matches in its own transaction, which survives the rollback of the event itself. */
@Component
class UnmatchedWebhooks {

    private final PaymentRepository payments;
    private final Clock clock;

    UnmatchedWebhooks(PaymentRepository payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(String eventId, String providerPaymentId) {
        payments.insertUnmatched(eventId, providerPaymentId, OffsetDateTime.now(clock));
    }
}
