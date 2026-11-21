package dev.shop.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.Identity;
import dev.shop.api.support.PaymentShopIT;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;

/** The signed provider webhook: authentication, size and type limits, dedupe, and each payment outcome. */
class WebhookIT extends PaymentShopIT {

    private static final String SECRET = "test-webhook-secret-test-webhook-secret";
    private static final String SUCCEEDED = "payment.succeeded";
    private static final String FAILED = "payment.failed";

    @Autowired
    private FilterChainProxy filterChainProxy;

    private AdminClient client;
    private String orderId;
    private UUID paymentId;

    @BeforeEach
    void anOrderWithAnOpenPayment() {
        client = customer("webhook-payer");
        orderId = placeOrderFor(client, LAMP, 2).get("id").asString();
        startPayment(client, orderId);
        paymentId = paymentOf(orderId);
    }

    @Test
    void theWebhookChainHasNoSessionLoginOrCsrfFilters() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/webhook");
        request.setServletPath("/api/payments/webhook");

        var chain = filterChainProxy.getFilterChains().stream()
                .filter(candidate -> candidate.matches(request))
                .findFirst()
                .orElseThrow();

        assertThat(chain.getFilters())
                .extracting(filter -> filter.getClass().getSimpleName())
                .doesNotContain(
                        "RevalidationFilter",
                        "AbsoluteSessionTimeoutFilter",
                        "CsrfFilter",
                        "OAuth2LoginAuthenticationFilter",
                        "SessionManagementFilter",
                        "AnonymousAuthenticationFilter");
    }

    @Test
    void aValidSuccessPaysTheOrderAndTakesTheHeldStock() {
        long versionBefore = stockVersion(lampId);

        HttpResponse<String> response = deliver(newEventId(), SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(AdminClient.json(response).get("received").asBoolean()).isTrue();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(onHand(lampId)).isEqualTo(8);
        assertThat(reserved(lampId)).isZero();
        assertThat(stockVersion(lampId)).isGreaterThan(versionBefore);
        assertStockInvariant();
        var order = orderJson(client, orderId);
        assertThat(order.get("status").asString()).isEqualTo("PAID");
        assertThat(order.get("paymentStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(order.get("latePayment").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT paid_payment_id FROM orders WHERE id = :id")
                        .param("id", UUID.fromString(orderId))
                        .query(UUID.class)
                        .single())
                .isEqualTo(paymentId);
    }

    static Stream<Arguments> badSignatures() {
        long now = System.currentTimeMillis() / 1000;
        return Stream.of(
                Arguments.of("no header", null),
                Arguments.of("empty header", ""),
                Arguments.of("wrong secret", "WRONG-SECRET"),
                Arguments.of("timestamp 301 s old", "STALE"),
                Arguments.of("timestamp 301 s ahead", "AHEAD"),
                Arguments.of("two v1 values", "t=" + now + ",v1=" + "a".repeat(64) + ",v1=" + "b".repeat(64)),
                Arguments.of("two t values", "t=" + now + ",t=" + now + ",v1=" + "a".repeat(64)),
                Arguments.of("only a timestamp", "t=" + now),
                Arguments.of("only a digest", "v1=" + "a".repeat(64)),
                Arguments.of("non-hex digest", "t=" + now + ",v1=" + "z".repeat(64)),
                Arguments.of("uppercase digest", "t=" + now + ",v1=" + "A".repeat(64)),
                Arguments.of("short digest", "t=" + now + ",v1=abcd"),
                Arguments.of("eleven digit timestamp", "t=12345678901,v1=" + "a".repeat(64)),
                Arguments.of("signed timestamp", "t=-1,v1=" + "a".repeat(64)),
                Arguments.of("unknown element", "t=" + now + ",v1=" + "a".repeat(64) + ",v2=ff"),
                Arguments.of("right shape, wrong digest", "t=" + now + ",v1=" + "a".repeat(64)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badSignatures")
    void aBadSignatureIsRefusedWithOneFixedAnswerAndNoEffect(String name, String header) {
        byte[] body = eventBody(newEventId(), SUCCEEDED, providerPaymentOf(paymentId), paymentId, 2 * LAMP_PRICE, "EUR")
                .getBytes(StandardCharsets.UTF_8);
        long now = clock.instant().getEpochSecond();
        String sent = switch (String.valueOf(header)) {
            case "WRONG-SECRET" -> hmacHeader("another-secret-another-secret-another", now, body);
            case "STALE" -> hmacHeader(SECRET, now - 301, body);
            case "AHEAD" -> hmacHeader(SECRET, now + 301, body);
            default -> header;
        };

        HttpResponse<String> response = postWebhook(body, sent, "Content-Type", "application/json");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(AdminClient.json(response).get("type").asString()).isEqualTo("tag:shop.dev,2026:webhook-signature");
        assertThat(AdminClient.json(response).get("detail").asString()).isEqualTo("The signature is not valid");
        assertNoEffect();
    }

    @Test
    void aTamperedBodyAndTwoSignatureHeadersAreRefused() {
        byte[] body = eventBody(newEventId(), SUCCEEDED, providerPaymentOf(paymentId), paymentId, 2 * LAMP_PRICE, "EUR")
                .getBytes(StandardCharsets.UTF_8);
        String signature = signature(body);
        byte[] tampered =
                new String(body, StandardCharsets.UTF_8).replace("3000", "1").getBytes(StandardCharsets.UTF_8);

        assertThat(postWebhook(tampered, signature, "Content-Type", "application/json")
                        .statusCode())
                .isEqualTo(401);
        assertThat(postWebhook(body, signature, "Content-Type", "application/json", "Shop-Signature", signature)
                        .statusCode())
                .isEqualTo(401);
        assertNoEffect();
    }

    @Test
    void theSameEventIdTwiceHasOneEffect() {
        String eventId = newEventId();

        deliver(eventId, SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");
        HttpResponse<String> second = deliver(eventId, SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(onHand(lampId)).isEqualTo(8);
        assertThat(count("payment_webhook_event")).isEqualTo(1);
    }

    @Test
    void theSamePaymentSucceedingUnderTwoEventIdsChangesNothingTheSecondTime() {
        deliver(newEventId(), SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");
        deliver(newEventId(), SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");

        assertThat(onHand(lampId)).isEqualTo(8);
        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(count("payment")).isEqualTo(1);
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
    }

    @Test
    void aFailureAfterSuccessChangesNothing() {
        deliver(newEventId(), SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");

        assertThat(deliver(newEventId(), FAILED, paymentId, 2 * LAMP_PRICE, "EUR")
                        .statusCode())
                .isEqualTo(200);

        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
    }

    @Test
    void aFailureLeavesTheOrderPendingAndALaterSuccessStillPaysIt() {
        deliver(newEventId(), FAILED, paymentId, 2 * LAMP_PRICE, "EUR");

        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("FAILED");
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(orderJson(client, orderId).get("paymentStatus").asString()).isEqualTo("FAILED");
        assertThat(deliver(newEventId(), FAILED, paymentId, 2 * LAMP_PRICE, "EUR")
                        .statusCode())
                .isEqualTo(200);

        deliver(newEventId(), SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");

        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
    }

    @Test
    void anAmountOrCurrencyMismatchLeavesTheOrderPendingAndRefundsThePayment() {
        deliver(newEventId(), SUCCEEDED, paymentId, 2 * LAMP_PRICE - 1, "EUR");

        awaitPayment(paymentId, "REFUNDED");
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(lampId)).isEqualTo(2);
        assertThat(onHand(lampId)).isEqualTo(10);
        assertThat(jdbc.sql("SELECT received_amount_minor FROM payment WHERE id = :id")
                        .param("id", paymentId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2 * LAMP_PRICE - 1);

        String other = placeOrderFor(client, NOTEBOOK, 1).get("id").asString();
        startPayment(client, other);
        UUID otherPayment = paymentOf(other);
        deliver(newEventId(), SUCCEEDED, otherPayment, NOTEBOOK_PRICE, "USD");

        awaitPayment(otherPayment, "REFUNDED");
        assertThat(orderStatusInDatabase(other)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void aSecondPaymentForAPaidOrderIsRefundedAndTheOrderStaysPaid() {
        deliver(newEventId(), SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");
        UUID second = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payment (id, order_id, provider, provider_payment_id, status, amount_minor, currency,
                                             created_at, updated_at)
                        VALUES (:id, :order, 'fake', 'pay_extra', 'CREATED', :amount, 'EUR', now(), now())
                        """)
                .param("id", second)
                .param("order", UUID.fromString(orderId))
                .param("amount", 2 * LAMP_PRICE)
                .update();

        deliver(newEventId(), SUCCEEDED, "pay_extra", second, 2 * LAMP_PRICE, "EUR");

        awaitPayment(second, "REFUNDED");
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(onHand(lampId)).isEqualTo(8);
    }

    @Test
    void aSeparateCaptureForTheSameMerchantReferenceIsRefundedAndChangesNeitherOrderNorStock() {
        assertThat(deliver(newEventId(), FAILED, "pay_second", paymentId, 2 * LAMP_PRICE, "EUR")
                        .statusCode())
                .isEqualTo(200);
        assertThat(count("payment")).isEqualTo(1);

        assertThat(deliver(newEventId(), SUCCEEDED, "pay_second", paymentId, 2 * LAMP_PRICE, "EUR")
                        .statusCode())
                .isEqualTo(200);

        UUID refund = jdbc.sql("SELECT id FROM payment WHERE provider_payment_id = 'pay_second'")
                .query(UUID.class)
                .single();
        awaitPayment(refund, "REFUNDED");
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("CREATED");
        assertThat(reserved(lampId)).isEqualTo(2);
    }

    @Test
    void anEventFoundOnlyByMerchantReferenceAdoptsTheProviderPaymentId() {
        jdbc.sql("UPDATE payment SET provider_payment_id = NULL, redirect_url = NULL WHERE id = :id")
                .param("id", paymentId)
                .update();

        deliver(newEventId(), SUCCEEDED, "pay_adopted", paymentId, 2 * LAMP_PRICE, "EUR");

        assertThat(providerPaymentOf(paymentId)).isEqualTo("pay_adopted");
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
    }

    @Test
    void anEventNoPaymentMatchesIsANotFoundTheProviderRetriesAndIsRecorded() {
        String eventId = newEventId();

        HttpResponse<String> response = deliver(eventId, SUCCEEDED, "pay_unknown", UUID.randomUUID(), 1, "EUR");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(AdminClient.json(response).get("type").asString()).isEqualTo("tag:shop.dev,2026:payment-not-found");
        assertThat(count("payment_webhook_unmatched")).isEqualTo(1);
        assertThat(count("payment_webhook_event")).isZero();

        HttpResponse<String> retry =
                deliver(eventId, SUCCEEDED, providerPaymentOf(paymentId), paymentId, 2 * LAMP_PRICE, "EUR");

        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
    }

    @Test
    void aSignedBodyOver16KiBIsAcceptedAndOver64KiBIsRefused() {
        String event =
                eventBody(newEventId(), SUCCEEDED, providerPaymentOf(paymentId), paymentId, 2 * LAMP_PRICE, "EUR");
        byte[] padded = (event + " ".repeat(20 * 1024)).getBytes(StandardCharsets.UTF_8);
        byte[] tooLarge = (event + " ".repeat(64 * 1024)).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> refused = postWebhook(tooLarge, signature(tooLarge), "Content-Type", "application/json");
        assertThat(refused.statusCode()).isEqualTo(413);
        assertThat(refused.headers().firstValue("Connection")).hasValue("close");
        assertThat(AdminClient.json(refused).get("type").asString()).isEqualTo("tag:shop.dev,2026:body-too-large");
        assertNoEffect();

        HttpResponse<String> accepted = postWebhook(padded, signature(padded), "Content-Type", "application/json");
        assertThat(accepted.statusCode()).isEqualTo(200);
    }

    @Test
    void aStreamedBodyIsCountedAsItIsReadAndRefusedAtOneByteOver() {
        String event =
                eventBody(newEventId(), SUCCEEDED, providerPaymentOf(paymentId), paymentId, 2 * LAMP_PRICE, "EUR");
        byte[] exactly = (event + " ".repeat(64 * 1024 - event.length())).getBytes(StandardCharsets.UTF_8);
        byte[] oneOver = (event + " ".repeat(64 * 1024 - event.length() + 1)).getBytes(StandardCharsets.UTF_8);

        assertThat(postWebhookChunked(oneOver, signature(oneOver)).statusCode()).isEqualTo(413);
        assertNoEffect();
        assertThat(postWebhookChunked(exactly, signature(exactly)).statusCode()).isEqualTo(200);
    }

    @Test
    void aSuccessForAnOrderAlreadyWaitingForItsRefundIsRefundedToo() {
        jdbc.sql("UPDATE orders SET status = 'REFUND_PENDING' WHERE id = :id")
                .param("id", UUID.fromString(orderId))
                .update();

        deliver(newEventId(), SUCCEEDED, paymentId, 2 * LAMP_PRICE, "EUR");

        awaitPayment(paymentId, "REFUNDED");
        await().atMost(Duration.ofSeconds(20)).until(() -> "REFUNDED".equals(orderStatusInDatabase(orderId)));
    }

    @Test
    void aBodyThatIsNotJsonIsRefusedBeforeItIsRead() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = signature(body);

        HttpResponse<String> text = postWebhook(body, signature, "Content-Type", "text/plain");
        assertThat(text.statusCode()).isEqualTo(415);
        assertThat(text.headers().firstValue("Connection")).hasValue("close");
        assertThat(postWebhook(body, signature).statusCode()).isEqualTo(415);
        assertThat(postWebhook(body, signature, "Content-Type", "not a media type")
                        .statusCode())
                .isEqualTo(415);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedEvents")
    void aSignedButMalformedEventIsA400(String name, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = postWebhook(body, signature(body), "Content-Type", "application/json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertNoEffect();
    }

    static Stream<Arguments> malformedEvents() {
        return Stream.of(
                Arguments.of("not json", "{"),
                Arguments.of("json null", "null"),
                Arguments.of("unknown type", "{\"id\":\"e\",\"type\":\"payment.exploded\",\"created\":1,\"data\":{}}"),
                Arguments.of("no data", "{\"id\":\"e\",\"type\":\"payment.failed\",\"created\":1}"),
                Arguments.of(
                        "data without a payment id",
                        "{\"id\":\"e\",\"type\":\"payment.failed\",\"created\":1,\"data\":{\"currency\":\"EUR\"}}"),
                Arguments.of("negative amount", succeededEvent("e", "p", -1, "EUR")),
                Arguments.of("lowercase currency", succeededEvent("e", "p", 100, "eur")),
                Arguments.of("long currency", succeededEvent("e", "p", 100, "EURO")),
                Arguments.of("empty event id", succeededEvent("", "p", 100, "EUR")),
                Arguments.of("overlong event id", succeededEvent("e".repeat(101), "p", 100, "EUR")),
                Arguments.of("empty provider payment id", succeededEvent("e", "", 100, "EUR")),
                Arguments.of("overlong provider payment id", succeededEvent("e", "p".repeat(101), 100, "EUR")));
    }

    private static String succeededEvent(String id, String providerPaymentId, long amountMinor, String currency) {
        return "{\"id\":\"" + id + "\",\"type\":\"payment.succeeded\",\"created\":1,\"data\":{"
                + "\"providerPaymentId\":\"" + providerPaymentId + "\","
                + "\"merchantReference\":\"00000000-0000-0000-0000-000000000001\","
                + "\"amountMinor\":" + amountMinor + ",\"currency\":\"" + currency + "\"}}";
    }

    @Test
    void aSessionCookieSentAlongIsIgnoredAndNoCookieComesBack() {
        TestBrowser browser = signedInAs(Identity.customer().withSubject("cookie-bearer"));
        browser.get("/api/me");
        byte[] body = eventBody(newEventId(), SUCCEEDED, providerPaymentOf(paymentId), paymentId, 2 * LAMP_PRICE, "EUR")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = postWebhook(
                body,
                signature(body),
                "Content-Type",
                "application/json",
                "Cookie",
                "__Host-SESSION=" + browser.cookie("__Host-SESSION") + "; __Host-XSRF-TOKEN=x");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void anEncodedSpellingOfThePathReachesTheWebhookButOnlyUnderTheSmallLimit() {
        String event =
                eventBody(newEventId(), SUCCEEDED, providerPaymentOf(paymentId), paymentId, 2 * LAMP_PRICE, "EUR");
        byte[] small = event.getBytes(StandardCharsets.UTF_8);
        byte[] large = (event + " ".repeat(20 * 1024)).getBytes(StandardCharsets.UTF_8);

        assertThat(rawPost("/api/payments/%77ebhook", large, signature(large), "Content-Type", "application/json")
                        .statusCode())
                .isEqualTo(413);
        assertThat(rawPost("/api/payments/%77ebhook", small, signature(small), "Content-Type", "application/json")
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void everyOtherSpellingAndMethodOfThePathFallsToTheMainChain() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(rawPost("/api/payments/webhook/", body, signature(body), "Content-Type", "application/json")
                        .statusCode())
                .isEqualTo(403);
        assertThat(rawPost("/API/payments/webhook", body, signature(body), "Content-Type", "application/json")
                        .statusCode())
                .isEqualTo(403);
        assertThat(get("/api/payments/webhook").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) {
        return browser().get(path);
    }

    private void awaitPayment(UUID id, String status) {
        await().atMost(Duration.ofSeconds(20)).until(() -> status.equals(paymentStatusInDatabase(id)));
    }

    private void assertNoEffect() {
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatusInDatabase(paymentId)).isEqualTo("CREATED");
        assertThat(count("payment_webhook_event")).isZero();
        assertThat(onHand(lampId)).isEqualTo(10);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private static String hmacHeader(String secret, long seconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((seconds + ".").getBytes(StandardCharsets.US_ASCII));
            return "t=" + seconds + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
