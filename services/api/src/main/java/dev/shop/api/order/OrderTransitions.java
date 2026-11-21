package dev.shop.api.order;

import dev.shop.api.inventory.reservations.StockReservations;
import dev.shop.api.order.OrderRepository.OrderRow;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Applies one row of {@link OrderStateMachine}: the stock effect first, then the status, so no status is written for a
 * transition whose effect failed. Runs inside the caller's transaction, under the order lock.
 */
@Component
class OrderTransitions {

    private final OrderRepository orders;
    private final StockReservations reservations;

    OrderTransitions(OrderRepository orders, StockReservations reservations) {
        this.orders = orders;
        this.reservations = reservations;
    }

    /** Empty when the table refuses the pair; otherwise the transition that was applied. */
    Optional<Transition> apply(OrderRow order, OrderTrigger trigger) {
        return OrderStateMachine.next(order.status(), trigger).map(planned -> {
            Transition applied = applyEffect(order, planned)
                    ? planned
                    : OrderStateMachine.next(order.status(), OrderTrigger.PAY_LATE_NOT_TAKEABLE)
                            .orElseThrow();
            orders.updateStatus(List.of(order.id()), applied.next());
            return applied;
        });
    }

    private boolean applyEffect(OrderRow order, Transition transition) {
        switch (transition.effect()) {
            case RELEASE -> reservations.release(order.id());
            case CONSUME -> reservations.consume(order.id());
            case TAKE_LATE -> {
                return reservations.takeLate(order.id(), orders.reservationLines(order.id()));
            }
            case NONE -> {}
        }
        return true;
    }
}
