package dev.shop.api.payment;

import static dev.shop.api.payment.PaymentEvent.FAILURE;
import static dev.shop.api.payment.PaymentEvent.REFUND_DONE;
import static dev.shop.api.payment.PaymentEvent.REFUND_REQUESTED;
import static dev.shop.api.payment.PaymentEvent.SUCCESS;
import static dev.shop.api.payment.PaymentStatus.CREATED;
import static dev.shop.api.payment.PaymentStatus.FAILED;
import static dev.shop.api.payment.PaymentStatus.REFUNDED;
import static dev.shop.api.payment.PaymentStatus.REFUND_PENDING;
import static dev.shop.api.payment.PaymentStatus.SUCCEEDED;

import java.util.Map;
import java.util.Optional;

/**
 * Payment states only move forward: {@code CREATED -> SUCCEEDED | FAILED}, {@code FAILED -> SUCCEEDED},
 * {@code SUCCEEDED -> REFUND_PENDING -> REFUNDED}. A pair that is not listed is a no-op: an event for a payment that
 * is already in its target state or a later one changes nothing.
 */
final class PaymentStateMachine {

    private record Key(PaymentStatus from, PaymentEvent event) {}

    private static final Map<Key, PaymentStatus> TRANSITIONS = Map.of(
            new Key(CREATED, SUCCESS), SUCCEEDED,
            new Key(FAILED, SUCCESS), SUCCEEDED,
            new Key(CREATED, FAILURE), FAILED,
            new Key(SUCCEEDED, REFUND_REQUESTED), REFUND_PENDING,
            new Key(REFUND_PENDING, REFUND_DONE), REFUNDED);

    private PaymentStateMachine() {}

    static Optional<PaymentStatus> next(PaymentStatus from, PaymentEvent event) {
        return Optional.ofNullable(TRANSITIONS.get(new Key(from, event)));
    }
}
