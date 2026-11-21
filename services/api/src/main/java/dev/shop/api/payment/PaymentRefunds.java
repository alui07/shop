package dev.shop.api.payment;

import dev.shop.api.order.OrderPayments;
import dev.shop.api.payment.PaymentRepository.PaymentRow;
import dev.shop.api.payment.PaymentRepository.RefundTarget;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The two short transactions around a refund call, which itself runs outside any transaction. */
@Component
class PaymentRefunds {

    private final PaymentRepository payments;
    private final OrderPayments orders;
    private final Clock clock;

    PaymentRefunds(PaymentRepository payments, OrderPayments orders, Clock clock) {
        this.payments = payments;
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    RefundTarget target(UUID paymentId) {
        return payments.refundTarget(paymentId);
    }

    /** Moves the payment to refunded and tells the order, which finishes only when no other money is held. */
    @Transactional
    void complete(UUID orderId, UUID paymentId) {
        orders.lock(orderId);
        PaymentRow payment = payments.lockById(paymentId).orElseThrow();
        PaymentStateMachine.next(payment.status(), PaymentEvent.REFUND_DONE)
                .ifPresent(next -> payments.updateStatus(paymentId, next, OffsetDateTime.now(clock)));
        orders.refunded(orderId, paymentId, payments.otherMoneyHeld(orderId, paymentId));
    }
}
