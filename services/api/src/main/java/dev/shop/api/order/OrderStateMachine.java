package dev.shop.api.order;

import java.util.Map;
import java.util.Optional;

/**
 * Every status change an order can make, in one table; anything not listed is refused. Each listed change leaves
 * {@code PENDING_PAYMENT}, so each releases the stock held for the order.
 */
final class OrderStateMachine {

    private record Key(OrderStatus from, OrderTrigger trigger) {}

    private static final Map<Key, OrderStatus> TRANSITIONS = Map.of(
            new Key(OrderStatus.PENDING_PAYMENT, OrderTrigger.CANCEL), OrderStatus.CANCELLED,
            new Key(OrderStatus.PENDING_PAYMENT, OrderTrigger.EXPIRE), OrderStatus.EXPIRED);

    private OrderStateMachine() {}

    static Optional<OrderStatus> next(OrderStatus from, OrderTrigger trigger) {
        return Optional.ofNullable(TRANSITIONS.get(new Key(from, trigger)));
    }
}
