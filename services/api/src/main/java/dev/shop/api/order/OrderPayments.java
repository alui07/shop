package dev.shop.api.order;

import dev.shop.api.catalog.pricing.PricedProduct;
import dev.shop.api.catalog.pricing.ProductPricing;
import dev.shop.api.inventory.reservations.ReservationLine;
import dev.shop.api.order.OrderRepository.OrderRow;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How the payment module reaches orders. Only {@link #payable} belongs to a signed-in customer; the rest run for the
 * webhook and the listeners and take the order lock by id. Every method joins the caller's transaction.
 */
@Service
@NamedInterface("payments")
public class OrderPayments {

    private static final Set<OrderStatus> SETTLED =
            Set.of(OrderStatus.PAID, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED);

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final ProductPricing pricing;
    private final CheckoutProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrderPayments(
            OrderRepository orders,
            OrderTransitions transitions,
            ProductPricing pricing,
            CheckoutProperties properties,
            ApplicationEventPublisher events,
            Clock clock) {
        this.orders = orders;
        this.transitions = transitions;
        this.pricing = pricing;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Locks the customer's order and returns what to charge. An order past its hold is expired on the spot, and the
     * refusal keeps that change, so the caller's transaction must not roll back for it.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = OrderProblem.class)
    public PayableOrder payable(String subject, UUID orderId, String verifiedEmail) {
        OrderRow order = orders.lockOwned(orderId, subject).orElseThrow(OrderProblem::orderNotFound);
        if (order.status().presentedAt(order.expiresAt(), clock.instant()) != order.status()) {
            transitions.apply(order, OrderTrigger.EXPIRE);
            throw OrderProblem.orderState();
        }
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            throw OrderProblem.orderState();
        }
        orders.refreshEmail(orderId, verifiedEmail);
        return new PayableOrder(
                new PaymentAmount(order.total().amountMinor(), order.total().currency()),
                order.expiresAt(),
                "/orders/" + orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID orderId) {
        orders.lockById(orderId).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentCreated(UUID orderId) {
        orders.setPaymentStatus(orderId, OrderPaymentStatus.CREATED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentDecision paymentSucceeded(UUID orderId, UUID paymentId, PaymentAmount received) {
        OrderRow order = orders.lockById(orderId).orElseThrow();
        Instant now = clock.instant();
        OrderTrigger trigger = triggerFor(order, received, now);
        Transition applied = transitions.apply(order, trigger).orElseThrow();
        boolean accepted = applied.next() == OrderStatus.PAID
                && (trigger == OrderTrigger.PAY_IN_TIME || trigger == OrderTrigger.PAY_LATE_TAKEABLE);
        if (accepted) {
            orders.markPaid(orderId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), late(order, now));
            orders.setSettledBy(orderId, paymentId, OrderPaymentStatus.SUCCEEDED);
            events.publishEvent(new OrderPaid(UUID.randomUUID(), orderId));
            return PaymentDecision.ACCEPTED;
        }
        boolean refundStarts =
                applied.next() == OrderStatus.REFUND_PENDING && order.status() != OrderStatus.REFUND_PENDING;
        if (refundStarts || trigger == OrderTrigger.PAY_MISMATCH) {
            orders.setPaymentStatus(orderId, OrderPaymentStatus.REFUND_PENDING);
        }
        events.publishEvent(new RefundRequested(UUID.randomUUID(), orderId, paymentId));
        return PaymentDecision.REFUND;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentFailed(UUID orderId, UUID paymentId) {
        OrderRow order = orders.lockById(orderId).orElseThrow();
        transitions.apply(order, OrderTrigger.PAY_FAILED);
        if (order.status() == OrderStatus.PENDING_PAYMENT) {
            orders.setPaymentStatus(orderId, OrderPaymentStatus.FAILED);
        }
    }

    /** {@code otherMoneyHeld}: another payment of this order is still paid or waiting for its refund. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refunded(UUID orderId, UUID paymentId, boolean otherMoneyHeld) {
        OrderRow order = orders.lockById(orderId).orElseThrow();
        OrderTrigger trigger = otherMoneyHeld ? OrderTrigger.REFUND_DONE_PARTIAL : OrderTrigger.REFUND_DONE;
        Optional<Transition> applied = transitions.apply(order, trigger);
        boolean refundedOrder =
                applied.filter(t -> t.next() == OrderStatus.REFUNDED).isPresent();
        boolean refundedMismatch =
                applied.isEmpty() && !otherMoneyHeld && order.paymentStatus() == OrderPaymentStatus.REFUND_PENDING;
        if (refundedOrder || refundedMismatch) {
            orders.setPaymentStatus(orderId, OrderPaymentStatus.REFUNDED);
        }
    }

    private OrderTrigger triggerFor(OrderRow order, PaymentAmount received, Instant now) {
        if (SETTLED.contains(order.status())) {
            return OrderTrigger.PAY_OTHER_PAYMENT;
        }
        if (!received.equals(
                new PaymentAmount(order.total().amountMinor(), order.total().currency()))) {
            return OrderTrigger.PAY_MISMATCH;
        }
        if (order.status() == OrderStatus.PENDING_PAYMENT && order.expiresAt().isAfter(now)) {
            return OrderTrigger.PAY_IN_TIME;
        }
        return order.status() != OrderStatus.CANCELLED && withinWindow(order, now) && everyProductActive(order)
                ? OrderTrigger.PAY_LATE_TAKEABLE
                : OrderTrigger.PAY_LATE_NOT_TAKEABLE;
    }

    private boolean withinWindow(OrderRow order, Instant now) {
        return !now.isAfter(order.expiresAt().plus(properties.latePaymentWindow()));
    }

    private boolean everyProductActive(OrderRow order) {
        List<Long> productIds = orders.reservationLines(order.id()).stream()
                .map(ReservationLine::productId)
                .toList();
        return pricing.lockForCheckout(productIds).stream().allMatch(PricedProduct::active);
    }

    private static boolean late(OrderRow order, Instant now) {
        return !order.expiresAt().isAfter(now);
    }
}
