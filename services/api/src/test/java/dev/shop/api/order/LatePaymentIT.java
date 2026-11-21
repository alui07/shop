package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.PaymentShopIT;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A payment that arrives after the hold ran out, or for an order that is no longer open. */
class LatePaymentIT extends PaymentShopIT {

    private static final String SUCCEEDED = "payment.succeeded";

    @Autowired
    private ReservationExpiryJob expiryJob;

    private AdminClient client;

    @BeforeEach
    void aCustomer() {
        client = customer("late-payer");
    }

    @Test
    void aSuccessAfterTheHoldButBeforeTheJobTakesTheHeldStockAndMarksTheOrderLate() {
        Placed placed = placedAndStarted(LAMP, 2);
        clock.advance(Duration.ofMinutes(16));

        deliver(newEventId(), SUCCEEDED, placed.payment, 2 * LAMP_PRICE, "EUR");

        assertThat(orderStatusInDatabase(placed.orderId)).isEqualTo("PAID");
        assertThat(orderJson(client, placed.orderId).get("latePayment").asBoolean())
                .isTrue();
        assertThat(onHand(lampId)).isEqualTo(8);
        assertThat(reserved(lampId)).isZero();
        assertStockInvariant();
    }

    @Test
    void aSuccessForAnExpiredOrderWithStockTakesTheStockAndBumpsTheVersion() {
        Placed placed = placedAndStarted(LAMP, 2);
        clock.advance(Duration.ofMinutes(16));
        expiryJob.runOnce();
        assertThat(orderStatusInDatabase(placed.orderId)).isEqualTo("EXPIRED");
        long versionBefore = stockVersion(lampId);

        deliver(newEventId(), SUCCEEDED, placed.payment, 2 * LAMP_PRICE, "EUR");

        assertThat(orderStatusInDatabase(placed.orderId)).isEqualTo("PAID");
        assertThat(orderJson(client, placed.orderId).get("latePayment").asBoolean())
                .isTrue();
        assertThat(onHand(lampId)).isEqualTo(8);
        assertThat(reserved(lampId)).isZero();
        assertThat(stockVersion(lampId)).isGreaterThan(versionBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation WHERE order_id = :id AND status = 'CONSUMED'")
                        .param("id", UUID.fromString(placed.orderId))
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertStockInvariant();
    }

    @Test
    void anExpiredOrderWithOneLineShortIsRefundedAndNoStockRowChanges() {
        putLine(client, LAMP, 1);
        putLine(client, NOTEBOOK, 1);
        String orderId = placeOrder(client).get("id").asString();
        startPayment(client, orderId);
        UUID payment = paymentOf(orderId);
        clock.advance(Duration.ofMinutes(16));
        expiryJob.runOnce();
        jdbc.sql("UPDATE stock SET on_hand = 0 WHERE product_id = :id")
                .param("id", notebookId)
                .update();
        long lampVersion = stockVersion(lampId);
        long notebookVersion = stockVersion(notebookId);

        deliver(newEventId(), SUCCEEDED, payment, LAMP_PRICE + NOTEBOOK_PRICE, "EUR");

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(orderStatusInDatabase(orderId)));
        assertThat(onHand(lampId)).isEqualTo(10);
        assertThat(onHand(notebookId)).isZero();
        assertThat(stockVersion(lampId)).isEqualTo(lampVersion);
        assertThat(stockVersion(notebookId)).isEqualTo(notebookVersion);
        assertThat(paymentStatusInDatabase(payment)).isEqualTo("REFUNDED");
        assertStockInvariant();
    }

    @Test
    void aSuccessOutsideTheLateWindowIsRefunded() {
        Placed placed = placedAndStarted(LAMP, 1);
        clock.advance(Duration.ofMinutes(16));
        expiryJob.runOnce();
        clock.advance(Duration.ofMinutes(31));

        deliver(newEventId(), SUCCEEDED, placed.payment, LAMP_PRICE, "EUR");

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(orderStatusInDatabase(placed.orderId)));
        assertThat(onHand(lampId)).isEqualTo(10);
    }

    @Test
    void aSuccessForAnOrderWhoseProductWasDeactivatedIsRefunded() {
        Placed placed = placedAndStarted(LAMP, 1);
        clock.advance(Duration.ofMinutes(16));
        expiryJob.runOnce();
        jdbc.sql("UPDATE product SET active = false WHERE id = :id")
                .param("id", lampId)
                .update();

        deliver(newEventId(), SUCCEEDED, placed.payment, LAMP_PRICE, "EUR");

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(orderStatusInDatabase(placed.orderId)));
        assertThat(onHand(lampId)).isEqualTo(10);
    }

    @Test
    void anOverdueOrderTheJobHasNotRunOnIsReleasedAndRefundedWhenOutsideTheWindowOrAProductIsInactive() {
        Placed outside = placedAndStarted(LAMP, 1);
        clock.advance(Duration.ofMinutes(16 + 31));

        deliver(newEventId(), SUCCEEDED, outside.payment, LAMP_PRICE, "EUR");

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(orderStatusInDatabase(outside.orderId)));
        assertThat(reserved(lampId)).isZero();
        assertThat(onHand(lampId)).isEqualTo(10);

        clock.reset();
        Placed inactive = placedAndStarted(NOTEBOOK, 1);
        clock.advance(Duration.ofMinutes(16));
        jdbc.sql("UPDATE product SET active = false WHERE id = :id")
                .param("id", notebookId)
                .update();

        deliver(newEventId(), SUCCEEDED, inactive.payment, NOTEBOOK_PRICE, "EUR");

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(orderStatusInDatabase(inactive.orderId)));
        assertThat(reserved(notebookId)).isZero();
    }

    @Test
    void aSuccessForACancelledOrderIsRefunded() {
        Placed placed = placedAndStarted(LAMP, 1);
        client.post("/api/orders/" + placed.orderId + "/cancel", "");

        deliver(newEventId(), SUCCEEDED, placed.payment, LAMP_PRICE, "EUR");

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(orderStatusInDatabase(placed.orderId)));
        assertThat(orderJson(client, placed.orderId).get("paymentStatus").asString())
                .isEqualTo("REFUNDED");
        assertThat(onHand(lampId)).isEqualTo(10);
        assertThat(reserved(lampId)).isZero();
    }

    @Test
    void aMismatchOnAPendingOrderLeavesItPendingAndTheJobLaterExpiresIt() {
        Placed placed = placedAndStarted(LAMP, 1);

        deliver(newEventId(), SUCCEEDED, placed.payment, LAMP_PRICE + 1, "EUR");

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(paymentStatusInDatabase(placed.payment)));
        assertThat(orderStatusInDatabase(placed.orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(lampId)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(16));
        expiryJob.runOnce();

        assertThat(orderStatusInDatabase(placed.orderId)).isEqualTo("EXPIRED");
        assertThat(reserved(lampId)).isZero();
    }

    private Placed placedAndStarted(String slug, int quantity) {
        String orderId = placeOrderFor(client, slug, quantity).get("id").asString();
        startPayment(client, orderId);
        return new Placed(orderId, paymentOf(orderId));
    }

    private record Placed(String orderId, UUID payment) {}
}
