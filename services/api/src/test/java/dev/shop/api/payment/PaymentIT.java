package dev.shop.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.Identity;
import dev.shop.api.support.PaymentShopIT;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Starting a payment, and the provider failing in the middle of starting or refunding one. */
class PaymentIT extends PaymentShopIT {

    private static final String ORDER_STATE = "tag:shop.dev,2026:order-state";

    @MockitoSpyBean
    private PaymentProvider provider;

    @Autowired
    private dev.shop.api.web.EventRetryJob retryJob;

    @AfterEach
    void restoreTheProvider() {
        org.mockito.Mockito.reset(provider);
    }

    @Test
    void startingAPaymentReturnsTheProviderPageAndRecordsAnOpenPayment() {
        AdminClient client = customerWithEmail("payer", "payer@example.test");
        String orderId = placeOrderFor(client, LAMP, 2).get("id").asString();

        HttpResponse<String> response = client.post("/api/orders/" + orderId + "/payment", "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(AdminClient.json(response).get("redirectUrl").asString()).startsWith("/fake-psp/");
        UUID paymentId = paymentOf(orderId);
        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("CREATED");
        assertThat(jdbc.sql("SELECT payment_status FROM orders WHERE id = :id")
                        .param("id", UUID.fromString(orderId))
                        .query(String.class)
                        .single())
                .isEqualTo("CREATED");
        assertThat(jdbc.sql("SELECT amount_minor FROM payment WHERE id = :id")
                        .param("id", paymentId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2 * LAMP_PRICE);
        assertThat(orderJson(client, orderId).get("paymentStatus").asString()).isEqualTo("CREATED");
        assertThat(jdbc.sql("SELECT email FROM orders WHERE id = :id")
                        .param("id", UUID.fromString(orderId))
                        .query(String.class)
                        .single())
                .isEqualTo("payer@example.test");
    }

    @Test
    void startingTwiceReturnsTheSameSessionAndCreatesOnePayment() {
        AdminClient client = customer("double-clicker");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();

        String first = startPayment(client, orderId);
        String second = startPayment(client, orderId);

        assertThat(second).isEqualTo(first);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(count("fake_psp_payment")).isEqualTo(1);
    }

    @Test
    void anUnverifiedEmailClearsTheAddressTheOrderWouldBeMailedTo() {
        AdminClient verified = customerWithEmail("changer", "old@example.test");
        String orderId = placeOrderFor(verified, LAMP, 1).get("id").asString();
        startPayment(verified, orderId);

        AdminClient unverified = new AdminClient(signedInAs(Identity.customer()
                .withSubject("changer")
                .withEmail("new@example.test")
                .withEmailVerified(false)));
        startPayment(unverified, orderId);

        assertThat(jdbc.sql("SELECT email FROM orders WHERE id = :id")
                        .param("id", UUID.fromString(orderId))
                        .query(String.class)
                        .optional())
                .isEmpty();
    }

    @Test
    void anotherCustomersOrAnUnknownOrderIsNotFound() {
        AdminClient owner = customer("owner");
        String orderId = placeOrderFor(owner, LAMP, 1).get("id").asString();

        assertThat(customer("stranger")
                        .post("/api/orders/" + orderId + "/payment", "")
                        .statusCode())
                .isEqualTo(404);
        assertThat(owner.post("/api/orders/" + UUID.randomUUID() + "/payment", "")
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void anOrderThatIsNotAwaitingPaymentIsAConflict() {
        AdminClient client = customer("canceller");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        client.post("/api/orders/" + orderId + "/cancel", "");

        HttpResponse<String> response = client.post("/api/orders/" + orderId + "/payment", "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(AdminClient.json(response).get("type").asString()).isEqualTo(ORDER_STATE);
        assertThat(count("payment")).isZero();
    }

    @Test
    void anOrderPastItsHoldIsExpiredOnTheSpotAndStockIsReleased() {
        AdminClient client = customer("late-starter");
        String orderId = placeOrderFor(client, LAMP, 3).get("id").asString();
        clock.advance(Duration.ofMinutes(16));

        HttpResponse<String> response = client.post("/api/orders/" + orderId + "/payment", "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("EXPIRED");
        assertThat(reserved(lampId)).isZero();
        assertThat(count("payment")).isZero();
    }

    @Test
    void aProviderFailureWhileCreatingTheSessionLeavesAnOpenPaymentThatTheNextStartCompletes() {
        AdminClient client = customer("unlucky");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        doThrow(new IllegalStateException("provider down"))
                .doCallRealMethod()
                .when(provider)
                .createPayment(any(), anyLong(), any(), any(), any());

        assertThat(client.post("/api/orders/" + orderId + "/payment", "").statusCode())
                .isEqualTo(500);
        assertThat(jdbc.sql("SELECT redirect_url FROM payment WHERE order_id = :id")
                        .param("id", UUID.fromString(orderId))
                        .query(String.class)
                        .optional())
                .isEmpty();

        String redirectUrl = startPayment(client, orderId);

        assertThat(redirectUrl).startsWith("/fake-psp/");
        assertThat(count("payment")).isEqualTo(1);
    }

    @Test
    void aFailedRefundIsRetriedAndThenFinishesTheOrder() {
        AdminClient client = customer("refund-retry");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        startPayment(client, orderId);
        UUID paymentId = paymentOf(orderId);
        doThrow(new IllegalStateException("provider down for " + "secret-detail"))
                .when(provider)
                .refund(any(), anyLong(), any(), any());

        assertThat(deliver(newEventId(), "payment.succeeded", paymentId, 1, "EUR")
                        .statusCode())
                .isEqualTo(200);

        await().atMost(Duration.ofSeconds(20)).until(() -> count("event_publication WHERE status = 'FAILED'") == 1);
        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("REFUND_PENDING");
        org.mockito.Mockito.reset(provider);
        clock.advance(Duration.ofMinutes(5));
        retryJob.runOnce();

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(paymentStatusInDatabase(paymentId)));
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PENDING_PAYMENT");
    }

    private int count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Integer.class)
                .single();
    }
}
