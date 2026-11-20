package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every (status, trigger) pair, refusals included. */
class OrderStateMachineTest {

    static Stream<Arguments> everyPair() {
        return Stream.of(
                Arguments.of(OrderStatus.PENDING_PAYMENT, OrderTrigger.CANCEL, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.PENDING_PAYMENT, OrderTrigger.EXPIRE, OrderStatus.EXPIRED),
                Arguments.of(OrderStatus.CANCELLED, OrderTrigger.CANCEL, null),
                Arguments.of(OrderStatus.CANCELLED, OrderTrigger.EXPIRE, null),
                Arguments.of(OrderStatus.EXPIRED, OrderTrigger.CANCEL, null),
                Arguments.of(OrderStatus.EXPIRED, OrderTrigger.EXPIRE, null));
    }

    @ParameterizedTest(name = "{0} on {1} gives {2}")
    @MethodSource("everyPair")
    void eachPairHasTheListedOutcome(OrderStatus from, OrderTrigger trigger, OrderStatus expected) {
        assertThat(OrderStateMachine.next(from, trigger)).isEqualTo(Optional.ofNullable(expected));
    }

    @Test
    void theTableCoversEveryStatusAndTrigger() {
        assertThat(everyPair().count()).isEqualTo((long) OrderStatus.values().length * OrderTrigger.values().length);
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
