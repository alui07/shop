package dev.shop.api.payment;

import dev.shop.api.order.OrderPayments;
import dev.shop.api.order.PayableOrder;
import dev.shop.api.payment.PaymentRepository.PaymentRow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/** The two short transactions of starting a payment; the provider call happens between them, outside any transaction. */
@Component
class PaymentOpening {

    private final OrderPayments orders;
    private final PaymentRepository payments;
    private final PaymentProperties properties;
    private final Clock clock;

    PaymentOpening(OrderPayments orders, PaymentRepository payments, PaymentProperties properties, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Under the order lock, returns the open payment or creates one. Concurrent starts for one order queue on the lock,
     * so the second sees the first's row. The order's own refusals (expired, not payable) keep their writes.
     */
    @Transactional(noRollbackFor = ErrorResponseException.class)
    OpenPayment open(String subject, String verifiedEmail, UUID orderId) {
        PayableOrder order = orders.payable(subject, orderId, verifiedEmail);
        PaymentRow payment = payments.findOpen(orderId).orElseGet(() -> create(orderId, order));
        return new OpenPayment(payment, order);
    }

    @Transactional
    void attach(UUID paymentId, ProviderSession session) {
        payments.setSession(paymentId, session.providerPaymentId(), session.redirectUrl());
    }

    private PaymentRow create(UUID orderId, PayableOrder order) {
        UUID id = UUID.randomUUID();
        payments.insertCreated(
                id,
                orderId,
                properties.provider(),
                order.amount().amountMinor(),
                order.amount().currency(),
                OffsetDateTime.now(clock));
        orders.paymentCreated(orderId);
        return payments.findById(id).orElseThrow();
    }
}
