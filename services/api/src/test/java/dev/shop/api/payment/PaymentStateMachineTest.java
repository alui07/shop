package dev.shop.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every (state, event) pair: the listed moves, and no-ops for everything already at or past the target. */
class PaymentStateMachineTest {

    private static final Map<PaymentStatus, Map<PaymentEvent, PaymentStatus>> LISTED = Map.of(
            PaymentStatus.CREATED,
            Map.of(PaymentEvent.SUCCESS, PaymentStatus.SUCCEEDED, PaymentEvent.FAILURE, PaymentStatus.FAILED),
            PaymentStatus.FAILED,
            Map.of(PaymentEvent.SUCCESS, PaymentStatus.SUCCEEDED),
            PaymentStatus.SUCCEEDED,
            Map.of(PaymentEvent.REFUND_REQUESTED, PaymentStatus.REFUND_PENDING),
            PaymentStatus.REFUND_PENDING,
            Map.of(PaymentEvent.REFUND_DONE, PaymentStatus.REFUNDED),
            PaymentStatus.REFUNDED,
            Map.of());

    static Stream<Arguments> everyPair() {
        return Stream.of(PaymentStatus.values())
                .flatMap(status -> Stream.of(PaymentEvent.values())
                        .map(event ->
                                Arguments.of(status, event, LISTED.get(status).get(event))));
    }

    @ParameterizedTest(name = "{0} on {1} gives {2}")
    @MethodSource("everyPair")
    void eachPairIsAMoveOrANoOp(PaymentStatus from, PaymentEvent event, PaymentStatus expected) {
        assertThat(PaymentStateMachine.next(from, event)).isEqualTo(Optional.ofNullable(expected));
    }
}
