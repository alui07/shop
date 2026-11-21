package dev.shop.api.order;

import static dev.shop.api.order.OrderStatus.CANCELLED;
import static dev.shop.api.order.OrderStatus.EXPIRED;
import static dev.shop.api.order.OrderStatus.PAID;
import static dev.shop.api.order.OrderStatus.PENDING_PAYMENT;
import static dev.shop.api.order.OrderStatus.REFUNDED;
import static dev.shop.api.order.OrderStatus.REFUND_PENDING;
import static dev.shop.api.order.OrderTrigger.CANCEL;
import static dev.shop.api.order.OrderTrigger.EXPIRE;
import static dev.shop.api.order.OrderTrigger.PAY_FAILED;
import static dev.shop.api.order.OrderTrigger.PAY_IN_TIME;
import static dev.shop.api.order.OrderTrigger.PAY_LATE_NOT_TAKEABLE;
import static dev.shop.api.order.OrderTrigger.PAY_LATE_TAKEABLE;
import static dev.shop.api.order.OrderTrigger.PAY_MISMATCH;
import static dev.shop.api.order.OrderTrigger.PAY_OTHER_PAYMENT;
import static dev.shop.api.order.OrderTrigger.REFUND_DONE;
import static dev.shop.api.order.OrderTrigger.REFUND_DONE_PARTIAL;
import static dev.shop.api.order.StockEffect.CONSUME;
import static dev.shop.api.order.StockEffect.NONE;
import static dev.shop.api.order.StockEffect.RELEASE;
import static dev.shop.api.order.StockEffect.TAKE_LATE;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every status change an order can make, with its effect on stock, in one pure table; anything not listed is refused.
 * The caller works out the trigger from the facts under the order lock (window, product activity, amounts).
 */
final class OrderStateMachine {

    private record Key(OrderStatus from, OrderTrigger trigger) {}

    private static final Map<Key, Transition> TRANSITIONS = new HashMap<>();

    static {
        row(PENDING_PAYMENT, CANCEL, CANCELLED, RELEASE);
        row(PENDING_PAYMENT, EXPIRE, EXPIRED, RELEASE);
        row(PENDING_PAYMENT, PAY_IN_TIME, PAID, CONSUME);
        row(PENDING_PAYMENT, PAY_LATE_TAKEABLE, PAID, CONSUME);
        row(PENDING_PAYMENT, PAY_LATE_NOT_TAKEABLE, REFUND_PENDING, RELEASE);
        row(PENDING_PAYMENT, PAY_MISMATCH, PENDING_PAYMENT, NONE);
        row(PENDING_PAYMENT, PAY_FAILED, PENDING_PAYMENT, NONE);

        row(EXPIRED, PAY_LATE_TAKEABLE, PAID, TAKE_LATE);
        row(EXPIRED, PAY_LATE_NOT_TAKEABLE, REFUND_PENDING, NONE);
        row(EXPIRED, PAY_MISMATCH, EXPIRED, NONE);
        row(EXPIRED, PAY_FAILED, EXPIRED, NONE);

        row(CANCELLED, PAY_LATE_NOT_TAKEABLE, REFUND_PENDING, NONE);
        row(CANCELLED, PAY_MISMATCH, CANCELLED, NONE);
        row(CANCELLED, PAY_FAILED, CANCELLED, NONE);

        row(PAID, PAY_OTHER_PAYMENT, PAID, NONE);
        row(PAID, PAY_MISMATCH, PAID, NONE);
        row(PAID, PAY_FAILED, PAID, NONE);

        row(REFUND_PENDING, PAY_OTHER_PAYMENT, REFUND_PENDING, NONE);
        row(REFUND_PENDING, PAY_MISMATCH, REFUND_PENDING, NONE);
        row(REFUND_PENDING, PAY_FAILED, REFUND_PENDING, NONE);
        row(REFUND_PENDING, REFUND_DONE, REFUNDED, NONE);
        row(REFUND_PENDING, REFUND_DONE_PARTIAL, REFUND_PENDING, NONE);

        row(REFUNDED, PAY_OTHER_PAYMENT, REFUNDED, NONE);
        row(REFUNDED, PAY_MISMATCH, REFUNDED, NONE);
        row(REFUNDED, PAY_FAILED, REFUNDED, NONE);
    }

    private OrderStateMachine() {}

    private static void row(OrderStatus from, OrderTrigger trigger, OrderStatus next, StockEffect effect) {
        TRANSITIONS.put(new Key(from, trigger), new Transition(next, effect));
    }

    static Optional<Transition> next(OrderStatus from, OrderTrigger trigger) {
        return Optional.ofNullable(TRANSITIONS.get(new Key(from, trigger)));
    }
}
