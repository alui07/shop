package dev.shop.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.Identity;
import dev.shop.api.support.PaymentShopIT;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

/** The session store is down and a stale session cookie rides along: the webhook does not depend on either. */
@DirtiesContext
class WebhookValkeyIT extends PaymentShopIT {

    @Autowired
    private GenericContainer<?> valkey;

    @Test
    void aWebhookIsPaidWithTheSessionStoreDownAndASessionCookieSentAlong() {
        TestBrowser browser = signedInAs(Identity.customer().withSubject("valkey-payer"));
        AdminClient client = new AdminClient(browser);
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        startPayment(client, orderId);
        UUID payment = paymentOf(orderId);
        valkey.stop();
        byte[] body = eventBody(
                        newEventId(), "payment.succeeded", providerPaymentOf(payment), payment, LAMP_PRICE, "EUR")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = postWebhook(
                body,
                signature(body),
                "Content-Type",
                "application/json",
                "Cookie",
                "__Host-SESSION=" + browser.cookie("__Host-SESSION"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(orderStatusInDatabase(orderId)).isEqualTo("PAID");
        assertThat(client.get("/api/me").statusCode()).isEqualTo(503);
    }
}
