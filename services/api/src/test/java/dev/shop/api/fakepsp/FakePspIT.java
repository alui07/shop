package dev.shop.api.fakepsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.shop.api.payment.WebhookSigner;
import dev.shop.api.support.AdminClient;
import dev.shop.api.support.PaymentShopIT;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** The fake provider's page and the webhook it sends, over real HTTP. */
class FakePspIT extends PaymentShopIT {

    private static final String PAYMENT_NOT_FOUND = "tag:shop.dev,2026:payment-not-found";
    private static final String PAYMENT_STATE = "tag:shop.dev,2026:payment-state";

    @MockitoSpyBean
    private WebhookSigner spiedSigner;

    @Autowired
    private dev.shop.api.web.EventRetryJob retryJob;

    private AdminClient client;
    private String orderId;
    private String token;
    private final String email = "fake-" + UUID.randomUUID() + "@example.test";

    @BeforeEach
    void anOrderAtTheProviderPage() {
        client = customerWithEmail("fake-payer", email);
        orderId = placeOrderFor(client, LAMP, 2).get("id").asString();
        token = tokenOf(startPayment(client, orderId));
    }

    @AfterEach
    void restore() {
        org.mockito.Mockito.reset(spiedSigner);
    }

    @Test
    void thePageShowsWhatIsToBePaidAndWhereToReturn() {
        HttpResponse<String> response = client.get("/api/fake-psp/payments/" + token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode page = AdminClient.json(response);
        assertThat(page.get("amountMinor").asLong()).isEqualTo(2 * LAMP_PRICE);
        assertThat(page.get("currency").asString()).isEqualTo("EUR");
        assertThat(page.get("merchantReference").asString())
                .isEqualTo(paymentOf(orderId).toString());
        assertThat(page.get("status").asString()).isEqualTo("PENDING");
        assertThat(page.get("returnUrl").asString()).isEqualTo("/orders/" + orderId);
        Instant orderExpiry =
                Instant.parse(orderJson(client, orderId).get("expiresAt").asString());
        assertThat(Instant.parse(page.get("expiresAt").asString())).isEqualTo(orderExpiry.plus(Duration.ofMinutes(5)));
    }

    @Test
    void anUnknownTokenIsNotFoundOnBothCalls() {
        for (HttpResponse<String> response : List.of(
                client.get("/api/fake-psp/payments/nope"),
                client.post("/api/fake-psp/payments/nope/complete", "{\"outcome\":\"SUCCEEDED\"}"))) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(AdminClient.json(response).get("type").asString()).isEqualTo(PAYMENT_NOT_FOUND);
        }
    }

    @Test
    void completingOnceMarksTheOrderPaidThroughTheWebhookAndAnEmailArrives() {
        HttpResponse<String> response = complete("SUCCEEDED");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(AdminClient.json(response).get("status").asString()).isEqualTo("SUCCEEDED");
        await().atMost(Duration.ofSeconds(30)).until(() -> "PAID".equals(orderStatusInDatabase(orderId)));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> mailpit.messagesTo(email).size() == 1);
        assertThat(onHand(lampId)).isEqualTo(8);

        HttpResponse<String> again = complete("SUCCEEDED");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(AdminClient.json(again).get("type").asString()).isEqualTo(PAYMENT_STATE);
    }

    @Test
    void aDeclinedPaymentLeavesTheOrderPendingForAnotherTry() {
        HttpResponse<String> response = complete("DECLINED");

        assertThat(AdminClient.json(response).get("status").asString()).isEqualTo("DECLINED");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "FAILED".equals(paymentStatusInDatabase(paymentOf(orderId))));
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(orderJson(client, orderId).get("paymentStatus").asString()).isEqualTo("FAILED");
    }

    @Test
    void aSessionPastItsExpiryCannotBeCompleted() {
        clock.advance(Duration.ofMinutes(21));

        HttpResponse<String> response = complete("SUCCEEDED");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(AdminClient.json(response).get("type").asString()).isEqualTo(PAYMENT_STATE);
    }

    @Test
    void aWebhookTheShopRefusesIsRetriedWithTheSameBodyAndAFreshSignature() throws Exception {
        doThrow(new IllegalStateException("shop is down"))
                .doCallRealMethod()
                .when(spiedSigner)
                .header(any(), any());
        List<Instant> signedAt = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();

        complete("SUCCEEDED");
        await().atMost(Duration.ofSeconds(30)).until(() -> count("event_publication WHERE status = 'FAILED'") == 1);
        clock.advance(Duration.ofMinutes(10));
        retryJob.runOnce();
        await().atMost(Duration.ofSeconds(30)).until(() -> "PAID".equals(orderStatusInDatabase(orderId)));

        ArgumentCaptor<Instant> instants = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(spiedSigner, times(2)).header(instants.capture(), body.capture());
        signedAt.addAll(instants.getAllValues());
        bodies.addAll(body.getAllValues());
        assertThat(Duration.between(signedAt.get(0), signedAt.get(1))).isGreaterThanOrEqualTo(Duration.ofMinutes(9));
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
        assertThat(onHand(lampId)).isEqualTo(8);
        assertThat(count("payment_webhook_event")).isEqualTo(1);
    }

    private HttpResponse<String> complete(String outcome) {
        return client.post("/api/fake-psp/payments/" + token + "/complete", "{\"outcome\":\"" + outcome + "\"}");
    }

    private int count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Integer.class)
                .single();
    }
}
