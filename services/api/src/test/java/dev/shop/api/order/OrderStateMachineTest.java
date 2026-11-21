package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every (status, trigger) pair, refusals included, with the stock effect of each listed row. */
class OrderStateMachineTest {

    private static final OrderStatus PENDING = OrderStatus.PENDING_PAYMENT;

    /** The table written out by status, so a missing or extra row shows up as a failing pair. */
    private static final Map<OrderStatus, Map<OrderTrigger, Transition>> LISTED = Map.of(
            PENDING,
            Map.of(
                    OrderTrigger.CANCEL, new Transition(OrderStatus.CANCELLED, StockEffect.RELEASE),
                    OrderTrigger.EXPIRE, new Transition(OrderStatus.EXPIRED, StockEffect.RELEASE),
                    OrderTrigger.PAY_IN_TIME, new Transition(OrderStatus.PAID, StockEffect.CONSUME),
                    OrderTrigger.PAY_LATE_TAKEABLE, new Transition(OrderStatus.PAID, StockEffect.CONSUME),
                    OrderTrigger.PAY_LATE_NOT_TAKEABLE, new Transition(OrderStatus.REFUND_PENDING, StockEffect.RELEASE),
                    OrderTrigger.PAY_MISMATCH, new Transition(PENDING, StockEffect.NONE),
                    OrderTrigger.PAY_FAILED, new Transition(PENDING, StockEffect.NONE)),
            OrderStatus.EXPIRED,
            Map.of(
                    OrderTrigger.PAY_LATE_TAKEABLE, new Transition(OrderStatus.PAID, StockEffect.TAKE_LATE),
                    OrderTrigger.PAY_LATE_NOT_TAKEABLE, new Transition(OrderStatus.REFUND_PENDING, StockEffect.NONE),
                    OrderTrigger.PAY_MISMATCH, new Transition(OrderStatus.EXPIRED, StockEffect.NONE),
                    OrderTrigger.PAY_FAILED, new Transition(OrderStatus.EXPIRED, StockEffect.NONE)),
            OrderStatus.CANCELLED,
            Map.of(
                    OrderTrigger.PAY_LATE_NOT_TAKEABLE, new Transition(OrderStatus.REFUND_PENDING, StockEffect.NONE),
                    OrderTrigger.PAY_MISMATCH, new Transition(OrderStatus.CANCELLED, StockEffect.NONE),
                    OrderTrigger.PAY_FAILED, new Transition(OrderStatus.CANCELLED, StockEffect.NONE)),
            OrderStatus.PAID,
            Map.of(
                    OrderTrigger.PAY_OTHER_PAYMENT, new Transition(OrderStatus.PAID, StockEffect.NONE),
                    OrderTrigger.PAY_MISMATCH, new Transition(OrderStatus.PAID, StockEffect.NONE),
                    OrderTrigger.PAY_FAILED, new Transition(OrderStatus.PAID, StockEffect.NONE)),
            OrderStatus.REFUND_PENDING,
            Map.of(
                    OrderTrigger.PAY_OTHER_PAYMENT, new Transition(OrderStatus.REFUND_PENDING, StockEffect.NONE),
                    OrderTrigger.PAY_MISMATCH, new Transition(OrderStatus.REFUND_PENDING, StockEffect.NONE),
                    OrderTrigger.PAY_FAILED, new Transition(OrderStatus.REFUND_PENDING, StockEffect.NONE),
                    OrderTrigger.REFUND_DONE, new Transition(OrderStatus.REFUNDED, StockEffect.NONE),
                    OrderTrigger.REFUND_DONE_PARTIAL, new Transition(OrderStatus.REFUND_PENDING, StockEffect.NONE)),
            OrderStatus.REFUNDED,
            Map.of(
                    OrderTrigger.PAY_OTHER_PAYMENT, new Transition(OrderStatus.REFUNDED, StockEffect.NONE),
                    OrderTrigger.PAY_MISMATCH, new Transition(OrderStatus.REFUNDED, StockEffect.NONE),
                    OrderTrigger.PAY_FAILED, new Transition(OrderStatus.REFUNDED, StockEffect.NONE)));

    static Stream<Arguments> everyPair() {
        return Stream.of(OrderStatus.values())
                .flatMap(status -> Stream.of(OrderTrigger.values())
                        .map(trigger ->
                                Arguments.of(status, trigger, LISTED.get(status).get(trigger))));
    }

    @ParameterizedTest(name = "{0} on {1} gives {2}")
    @MethodSource("everyPair")
    void eachPairHasTheListedOutcome(OrderStatus from, OrderTrigger trigger, Transition expected) {
        assertThat(OrderStateMachine.next(from, trigger)).isEqualTo(Optional.ofNullable(expected));
    }

    @Test
    void theTableCoversEveryStatusAndTrigger() {
        assertThat(everyPair().count()).isEqualTo((long) OrderStatus.values().length * OrderTrigger.values().length);
    }

    @Test
    void aFallibleStockEffectAlwaysHasAFallbackRowThatCannotFail() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderTrigger trigger : OrderTrigger.values()) {
                OrderStateMachine.next(from, trigger)
                        .filter(transition -> transition.effect() == StockEffect.TAKE_LATE)
                        .ifPresent(transition -> assertThat(
                                        OrderStateMachine.next(from, OrderTrigger.PAY_LATE_NOT_TAKEABLE))
                                .hasValueSatisfying(fallback ->
                                        assertThat(fallback.effect()).isNotEqualTo(StockEffect.TAKE_LATE)));
            }
        }
    }

    @Test
    void onlyAnUnpaidOrderPastItsHoldReadsAsExpired() {
        Instant now = Instant.parse("2026-11-19T12:00:00Z");

        assertThat(OrderStatus.PENDING_PAYMENT.presentedAt(now.plusSeconds(1), now))
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(OrderStatus.PENDING_PAYMENT.presentedAt(now, now)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(OrderStatus.CANCELLED.presentedAt(now.minusSeconds(1), now)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(OrderStatus.EXPIRED.presentedAt(now.plusSeconds(1), now)).isEqualTo(OrderStatus.EXPIRED);
    }
}
