package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.PaymentShopIT;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/** Webhooks racing the expiry job, payment start and each other end in one consistent state. */
class PaymentRaceIT extends PaymentShopIT {

    private static final String SUCCEEDED = "payment.succeeded";

    @Autowired
    private ReservationExpiryJob expiryJob;

    @RepeatedTest(5)
    void aWebhookAndTheExpiryJobOnAnOverdueOrderEndInExactlyOnePaidState() throws Exception {
        AdminClient client = customer("race-expiry");
        String orderId = placeOrderFor(client, LAMP, 2).get("id").asString();
        startPayment(client, orderId);
        UUID payment = paymentOf(orderId);
        clock.advance(Duration.ofMinutes(16));

        race(() -> deliver(newEventId(), SUCCEEDED, payment, 2 * LAMP_PRICE, "EUR"), () -> {
            expiryJob.runOnce();
            return null;
        });

        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
        assertThat(paymentStatusInDatabase(payment)).isEqualTo("SUCCEEDED");
        assertThat(onHand(lampId)).isEqualTo(8);
        assertThat(reserved(lampId)).isZero();
        assertStockInvariant();
    }

    @RepeatedTest(5)
    void aWebhookAndAPaymentStartForTheSameOrderStayConsistent() throws Exception {
        AdminClient client = customer("race-start");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        startPayment(client, orderId);
        UUID payment = paymentOf(orderId);

        List<Object> results = race(
                () -> deliver(newEventId(), SUCCEEDED, payment, LAMP_PRICE, "EUR"),
                () -> client.post("/api/orders/" + orderId + "/payment", ""));

        assertThat(statusOf(results.get(0))).isEqualTo(200);
        assertThat(statusOf(results.get(1))).isIn(200, 409);
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
        assertThat(count("payment")).isEqualTo(1);
        assertThat(onHand(lampId)).isEqualTo(9);
        assertStockInvariant();
    }

    @RepeatedTest(5)
    void aMismatchedPaymentBeingRefundedWhileAnotherPaymentSucceedsLeavesOnePaidPayment() throws Exception {
        AdminClient client = customer("race-refund");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        startPayment(client, orderId);
        UUID mismatched = paymentOf(orderId);
        UUID second = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payment (id, order_id, provider, provider_payment_id, status, amount_minor, currency,
                                             created_at, updated_at)
                        VALUES (:id, :order, 'fake', 'pay_second', 'FAILED', :amount, 'EUR', now(), now())
                        """)
                .param("id", second)
                .param("order", UUID.fromString(orderId))
                .param("amount", LAMP_PRICE)
                .update();

        race(
                () -> deliver(newEventId(), SUCCEEDED, mismatched, LAMP_PRICE - 1, "EUR"),
                () -> deliver(newEventId(), SUCCEEDED, "pay_second", second, LAMP_PRICE, "EUR"));

        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(paymentStatusInDatabase(mismatched)));
        assertThat(paymentStatusInDatabase(second)).isEqualTo("SUCCEEDED");
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
        assertThat(onHand(lampId)).isEqualTo(9);
        assertStockInvariant();
    }

    @RepeatedTest(5)
    void twoConcurrentStartsShareOneSession() throws Exception {
        AdminClient client = customer("race-twice");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();

        List<Object> results = race(
                () -> client.post("/api/orders/" + orderId + "/payment", ""),
                () -> client.post("/api/orders/" + orderId + "/payment", ""));

        assertThat(statusOf(results.get(0))).isEqualTo(200);
        assertThat(statusOf(results.get(1))).isEqualTo(200);
        assertThat(AdminClient.json((HttpResponse<String>) results.get(0)).get("redirectUrl"))
                .isEqualTo(
                        AdminClient.json((HttpResponse<String>) results.get(1)).get("redirectUrl"));
        assertThat(count("fake_psp_payment")).isEqualTo(1);
        assertThat(count("payment")).isEqualTo(1);
    }

    @RepeatedTest(5)
    void theSameEventDeliveredManyTimesAtOnceHasOneEffect() throws Exception {
        AdminClient client = customer("race-duplicates");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        startPayment(client, orderId);
        UUID payment = paymentOf(orderId);
        String eventId = newEventId();
        List<Callable<Object>> deliveries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            deliveries.add(() -> deliver(eventId, SUCCEEDED, payment, LAMP_PRICE, "EUR"));
        }

        List<Object> results = raceAll(deliveries);

        assertThat(results).allSatisfy(result -> assertThat(statusOf(result)).isEqualTo(200));
        assertThat(onHand(lampId)).isEqualTo(9);
        assertThat(count("payment_webhook_event")).isEqualTo(1);
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
    }

    private static int statusOf(Object response) {
        return ((HttpResponse<?>) response).statusCode();
    }

    @SafeVarargs
    private List<Object> race(Callable<Object>... tasks) throws Exception {
        return raceAll(List.of(tasks));
    }

    private List<Object> raceAll(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
