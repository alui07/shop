package dev.shop.api.support;

import dev.shop.api.payment.WebhookSigner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Orders, payments and signed provider events, over real HTTP against the random port. */
public abstract class PaymentShopIT extends ShopIT {

    private static final HttpClient RAW_CLIENT = HttpClient.newHttpClient();

    @Autowired
    protected WebhookSigner signer;

    @Autowired
    protected MailpitContainer mailpit;

    /** Publications are asynchronous: wait for them before the next test truncates their tables. */
    @AfterEach
    void waitForEventsToSettle() {
        EventFixtures.awaitQuiet(jdbc);
    }

    protected AdminClient customerWithEmail(String subject, String email) {
        return new AdminClient(
                signedInAs(Identity.customer().withSubject(subject).withEmail(email)));
    }

    protected String startPayment(AdminClient client, String orderId) {
        return ok(client.post("/api/orders/" + orderId + "/payment", ""))
                .get("redirectUrl")
                .asString();
    }

    /** Places an order for one line and pays it through a signed webhook; returns the order id. */
    protected String paidOrderFor(AdminClient client, String slug, int quantity) {
        String orderId = placeOrderFor(client, slug, quantity).get("id").asString();
        startPayment(client, orderId);
        UUID payment = paymentOf(orderId);
        long total = jdbc.sql("SELECT amount_minor FROM payment WHERE id = :id")
                .param("id", payment)
                .query(Long.class)
                .single();
        deliver(newEventId(), "payment.succeeded", payment, total, "EUR");
        return orderId;
    }

    protected static String tokenOf(String redirectUrl) {
        return redirectUrl.substring("/fake-psp/".length());
    }

    protected UUID paymentOf(String orderId) {
        return jdbc.sql("SELECT id FROM payment WHERE order_id = :order ORDER BY created_at DESC LIMIT 1")
                .param("order", UUID.fromString(orderId))
                .query(UUID.class)
                .single();
    }

    protected String providerPaymentOf(UUID paymentId) {
        return jdbc.sql("SELECT provider_payment_id FROM payment WHERE id = :id")
                .param("id", paymentId)
                .query(String.class)
                .single();
    }

    protected String orderStatusInDatabase(String orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id")
                .param("id", UUID.fromString(orderId))
                .query(String.class)
                .single();
    }

    protected String paymentStatusInDatabase(UUID paymentId) {
        return jdbc.sql("SELECT status FROM payment WHERE id = :id")
                .param("id", paymentId)
                .query(String.class)
                .single();
    }

    protected int onHand(long productId) {
        return jdbc.sql("SELECT on_hand FROM stock WHERE product_id = :id")
                .param("id", productId)
                .query(Integer.class)
                .single();
    }

    protected long stockVersion(long productId) {
        return jdbc.sql("SELECT version FROM stock WHERE product_id = :id")
                .param("id", productId)
                .query(Long.class)
                .single();
    }

    protected static String eventBody(
            String eventId,
            String type,
            String providerPaymentId,
            UUID merchantReference,
            long amountMinor,
            String currency) {
        return """
                {"id":"%s","type":"%s","created":1,"data":{"providerPaymentId":"%s","merchantReference":"%s","amountMinor":%d,"currency":"%s"}}""".formatted(eventId, type, providerPaymentId, merchantReference, amountMinor, currency);
    }

    /** A correctly signed event for this payment, signed now. */
    protected HttpResponse<String> deliver(
            String eventId, String type, UUID paymentId, long amountMinor, String currency) {
        return deliver(eventId, type, providerPaymentOf(paymentId), paymentId, amountMinor, currency);
    }

    protected HttpResponse<String> deliver(
            String eventId,
            String type,
            String providerPaymentId,
            UUID merchantReference,
            long amountMinor,
            String currency) {
        byte[] body = eventBody(eventId, type, providerPaymentId, merchantReference, amountMinor, currency)
                .getBytes(StandardCharsets.UTF_8);
        return postWebhook(body, signature(body), "Content-Type", "application/json");
    }

    protected String signature(byte[] body) {
        try {
            return signer.header(clock.instant(), body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    protected HttpResponse<String> postWebhook(byte[] body, String signature, String... headers) {
        return rawPost("/api/payments/webhook", body, signature, headers);
    }

    protected HttpResponse<String> rawPost(String path, byte[] body, String signature, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (signature != null) {
            builder.header("Shop-Signature", signature);
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            return RAW_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("POST " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A body with no declared length, so it is counted as it is read. */
    protected HttpResponse<String> postWebhookChunked(byte[] body, String signature) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/payments/webhook"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Shop-Signature", signature)
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofByteArray(body)))
                .build();
        try {
            return RAW_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("chunked POST failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static String newEventId() {
        return "evt_" + UUID.randomUUID();
    }

    protected JsonNode orderJson(AdminClient client, String orderId) {
        return ok(client.get("/api/orders/" + orderId));
    }
}
