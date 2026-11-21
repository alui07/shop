package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.PaymentShopIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** The corners of {@link OrderPayments} the webhook flows do not reach: refunds that are not the last money held. */
class OrderPaymentsIT extends PaymentShopIT {

    @Autowired
    private OrderPayments payments;

    @Autowired
    private OrderSummaries summaries;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void anOrderStaysRefundPendingWhileOtherMoneyIsHeldAndThenFinishes() {
        UUID order = insertOrderRow("refund-holder", clock.instant().plusSeconds(900));
        jdbc.sql("UPDATE orders SET status = 'REFUND_PENDING' WHERE id = :id")
                .param("id", order)
                .update();

        transaction.executeWithoutResult(status -> payments.refunded(order, UUID.randomUUID(), true));
        assertThat(orderStatus(order)).isEqualTo("REFUND_PENDING");

        transaction.executeWithoutResult(status -> payments.refunded(order, UUID.randomUUID(), false));
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        assertThat(jdbc.sql("SELECT payment_status FROM orders WHERE id = :id")
                        .param("id", order)
                        .query(String.class)
                        .single())
                .isEqualTo("REFUNDED");
    }

    @Test
    void refundingAnExtraPaymentOfAPaidOrderChangesNothing() {
        UUID order = insertOrderRow("paid-holder", clock.instant().plusSeconds(900));
        jdbc.sql("UPDATE orders SET status = 'PAID' WHERE id = :id")
                .param("id", order)
                .update();

        transaction.executeWithoutResult(status -> payments.refunded(order, UUID.randomUUID(), false));

        assertThat(orderStatus(order)).isEqualTo("PAID");
    }

    @Test
    void aFailureOnAnOrderThatIsNotPendingLeavesItsPaymentStatusAlone() {
        UUID order = insertOrderRow("failed-holder", clock.instant().plusSeconds(900));
        jdbc.sql("UPDATE orders SET status = 'PAID', payment_status = 'SUCCEEDED' WHERE id = :id")
                .param("id", order)
                .update();

        transaction.executeWithoutResult(status -> payments.paymentFailed(order, UUID.randomUUID()));

        assertThat(jdbc.sql("SELECT payment_status FROM orders WHERE id = :id")
                        .param("id", order)
                        .query(String.class)
                        .single())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void aConfirmationNeedsAnAddress() {
        UUID withoutEmail = insertOrderRow("no-address", clock.instant().plusSeconds(900));
        UUID withEmail = insertPendingOrder(
                "has-address", clock.instant().plusSeconds(900), new long[] {lampId, 2}, new long[] {notebookId, 1});
        jdbc.sql("UPDATE orders SET email = 'has@example.test', total_minor = 3400 WHERE id = :id")
                .param("id", withEmail)
                .update();

        assertThat(summaries.confirmation(withoutEmail)).isEmpty();
        var details = summaries.confirmation(withEmail).orElseThrow();

        assertThat(details.email()).isEqualTo("has@example.test");
        assertThat(details.lines()).hasSize(2);
        assertThat(details.totalMinor()).isEqualTo(2 * LAMP_PRICE + NOTEBOOK_PRICE);
        assertThat(details.currency()).isEqualTo("EUR");
    }

    @Test
    void anAmountMismatchOnAPendingOrderShowsTheRefundAndLeavesNoPayingPayment() {
        UUID order = insertOrderRow("mismatch", clock.instant().plusSeconds(900));
        UUID payment = UUID.randomUUID();
        jdbc.sql("UPDATE orders SET payment_status = 'CREATED' WHERE id = :id")
                .param("id", order)
                .update();

        PaymentDecision decision = succeed(order, payment, 999_999);

        assertThat(decision).isEqualTo(PaymentDecision.REFUND);
        assertThat(orderStatus(order)).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatus(order)).isEqualTo("REFUND_PENDING");
        assertThat(paidPaymentId(order)).isNull();

        transaction.executeWithoutResult(status -> payments.refunded(order, payment, false));
        assertThat(orderStatus(order)).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatus(order)).isEqualTo("REFUNDED");
    }

    @Test
    void aRefundOfAMismatchWhileOtherMoneyIsHeldKeepsTheRefundPendingStatus() {
        UUID order = insertOrderRow("mismatch-held", clock.instant().plusSeconds(900));
        jdbc.sql("UPDATE orders SET payment_status = 'REFUND_PENDING' WHERE id = :id")
                .param("id", order)
                .update();

        transaction.executeWithoutResult(status -> payments.refunded(order, UUID.randomUUID(), true));

        assertThat(paymentStatus(order)).isEqualTo("REFUND_PENDING");
    }

    @Test
    void aPaymentThatCannotBeTakenLateIsRefundedWithoutBeingRecordedAsPaying() {
        UUID order = insertOrderRow("cancelled-paid", clock.instant().plusSeconds(900));
        jdbc.sql("UPDATE orders SET status = 'CANCELLED', total_minor = 500, currency = 'EUR' WHERE id = :id")
                .param("id", order)
                .update();

        PaymentDecision decision = succeed(order, UUID.randomUUID(), 500);

        assertThat(decision).isEqualTo(PaymentDecision.REFUND);
        assertThat(orderStatus(order)).isEqualTo("REFUND_PENDING");
        assertThat(paymentStatus(order)).isEqualTo("REFUND_PENDING");
        assertThat(paidPaymentId(order)).isNull();
    }

    /** The refund the decision asks for has no real payment behind it, so its publication is dropped. */
    private PaymentDecision succeed(UUID order, UUID payment, long amountMinor) {
        return transaction.execute(status -> {
            PaymentDecision decision = payments.paymentSucceeded(order, payment, new PaymentAmount(amountMinor, "EUR"));
            jdbc.sql("DELETE FROM event_publication").update();
            return decision;
        });
    }

    private String paymentStatus(UUID order) {
        return jdbc.sql("SELECT payment_status FROM orders WHERE id = :id")
                .param("id", order)
                .query(String.class)
                .single();
    }

    private UUID paidPaymentId(UUID order) {
        return jdbc.sql("SELECT paid_payment_id FROM orders WHERE id = :id")
                .param("id", order)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }
}
