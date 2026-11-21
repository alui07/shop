package dev.shop.api.order;

import dev.shop.api.inventory.reservations.StockReservations;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One batch of expiries in one transaction; the job calls it through the proxy, once per batch. */
@Component
class OrderExpiry {

    private final OrderRepository orders;
    private final StockReservations reservations;

    OrderExpiry(OrderRepository orders, StockReservations reservations) {
        this.orders = orders;
        this.reservations = reservations;
    }

    /**
     * Locks up to {@code limit} overdue orders that no other replica holds, gives back all their stock with one update
     * per product in ascending id, and marks them expired. Returns how many it took.
     */
    @Transactional
    int expireBatch(OffsetDateTime now, int limit) {
        List<UUID> ids = orders.lockExpired(now, limit);
        if (ids.isEmpty()) {
            return 0;
        }
        reservations.releaseAll(ids);
        orders.updateStatus(
                ids,
                OrderStateMachine.next(OrderStatus.PENDING_PAYMENT, OrderTrigger.EXPIRE)
                        .orElseThrow()
                        .next());
        return ids.size();
    }
}
