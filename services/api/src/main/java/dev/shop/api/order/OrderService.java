package dev.shop.api.order;

import dev.shop.api.order.OrderRepository.OrderRow;
import dev.shop.api.order.OrderRepository.SummaryRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Every status change runs under the order's row lock and goes through {@link OrderStateMachine}. */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
class OrderService {

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final Clock clock;

    OrderService(OrderRepository orders, OrderTransitions transitions, Clock clock) {
        this.orders = orders;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    OrderPage list(String subject, int page, int size) {
        Instant now = clock.instant();
        long totalItems = orders.count(subject);
        List<OrderSummary> items = orders.page(subject, size, (long) page * size).stream()
                .map(row -> summary(row, now))
                .toList();
        int totalPages = (int) ((totalItems + size - 1) / size);
        return new OrderPage(items, page, size, totalItems, totalPages);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Order get(String subject, UUID id) {
        OrderRow row = orders.findOwned(id, subject).orElseThrow(OrderProblem::orderNotFound);
        return view(row, row.status());
    }

    /**
     * An unpaid order past its hold is released and marked expired on the spot, and the answer is still 409: the
     * expiry is kept because the exception does not roll back.
     */
    @Transactional(noRollbackFor = OrderProblem.class)
    Order cancel(String subject, UUID id) {
        OrderRow row = orders.lockOwned(id, subject).orElseThrow(OrderProblem::orderNotFound);
        boolean overdue = row.status().presentedAt(row.expiresAt(), clock.instant()) != row.status();
        OrderTrigger trigger = overdue ? OrderTrigger.EXPIRE : OrderTrigger.CANCEL;
        OrderStatus next = transitions
                .apply(row, trigger)
                .orElseThrow(OrderProblem::orderState)
                .next();
        if (overdue) {
            throw OrderProblem.orderState();
        }
        return view(row, next);
    }

    private Order view(OrderRow row, OrderStatus status) {
        Instant now = clock.instant();
        return new Order(
                row.id(),
                status.presentedAt(row.expiresAt(), now),
                row.createdAt(),
                row.expiresAt(),
                orders.lines(row.id()),
                row.total(),
                row.paymentStatus(),
                row.latePayment());
    }

    private static OrderSummary summary(SummaryRow summary, Instant now) {
        OrderRow row = summary.order();
        return new OrderSummary(
                row.id(),
                row.status().presentedAt(row.expiresAt(), now),
                row.createdAt(),
                row.expiresAt(),
                summary.itemCount(),
                row.total());
    }
}
