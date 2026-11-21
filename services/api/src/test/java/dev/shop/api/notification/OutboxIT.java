package dev.shop.api.notification;

import static dev.shop.api.support.HttpGet.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.EventFixtures;
import dev.shop.api.support.Identity;
import dev.shop.api.support.PaymentShopIT;
import dev.shop.api.web.EventRetryJob;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** The confirmation email through the event registry: async, at least once, and never blocking a request. */
@ExtendWith(OutputCaptureExtension.class)
class OutboxIT extends PaymentShopIT {

    @MockitoSpyBean
    private JavaMailSender mailSender;

    @Autowired
    private EventRetryJob retryJob;

    private final String email = "buyer-" + UUID.randomUUID() + "@example.test";

    @AfterEach
    void leaveNothingFailedBehind() {
        mailpit.thaw();
        Mockito.reset(mailSender);
        jdbc.sql("DELETE FROM event_publication WHERE status <> 'COMPLETED'").update();
    }

    @Test
    void theConfirmationIsSentOnceFromAnAsyncListenerThread() {
        List<String> threads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
                    threads.add(Thread.currentThread().getName());
                    return invocation.callRealMethod();
                })
                .when(mailSender)
                .send(any(SimpleMailMessage.class));
        AdminClient client = customerWithEmail("mail-buyer", email);

        String orderId = paidOrderFor(client, LAMP, 2);

        JsonNode messages = awaitMessages(1);
        assertThat(messages.get(0).get("Subject").asString())
                .isEqualTo("Your order " + orderId.substring(0, 8) + " is confirmed");
        assertThat(threads).hasSize(1);
        assertThat(threads.get(0))
                .startsWith("task-")
                .isNotEqualTo(Thread.currentThread().getName());
        String text = AdminClient.json(mailpitMessage(messages.get(0).get("ID").asString()))
                .get("Text")
                .asString();
        assertThat(text).contains("2 x Desk Lamp at 15.00 EUR").contains("Total: 30.00 EUR");
        assertThat(text).contains(PUBLIC_ORIGIN + "/orders/" + orderId);
        await().atMost(Duration.ofSeconds(20)).until(() -> "SENT".equals(emailStatus(orderId)));
    }

    @Test
    void anOrderWithoutAVerifiedAddressSendsNothingAndCompletes() {
        AdminClient client = new AdminClient(signedInAs(
                Identity.customer().withSubject("no-address").withEmail(email).withEmailVerified(false)));

        paidOrderFor(client, LAMP, 1);

        EventFixtures.awaitQuiet(jdbc);
        assertThat(mailpit.messagesTo(email)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "a@example.test\nBcc: x@example.test",
                "a@example.test\rBcc: x@example.test",
                "not an address",
                "TOO_LONG"
            })
    void anUnsendableAddressIsNotMailedAndTheEventCompletes(String address) {
        AdminClient client = customerWithEmail("odd-address", email);
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        startPayment(client, orderId);
        jdbc.sql("UPDATE orders SET email = :email WHERE id = :id")
                .param("email", address.equals("TOO_LONG") ? "a".repeat(250) + "@example.test" : address)
                .param("id", UUID.fromString(orderId))
                .update();
        UUID payment = paymentOf(orderId);

        deliver(newEventId(), "payment.succeeded", payment, LAMP_PRICE, "EUR");

        EventFixtures.awaitQuiet(jdbc);
        assertThat(mailpit.messagesTo(email)).isEmpty();
        assertThat(count("order_email")).isZero();
    }

    @Test
    void aHungMailServerNeverBlocksRequestsAndTheMailArrivesOnceAfterItRecovers() {
        AdminClient client = customerWithEmail("hung-buyer", email);
        mailpit.freeze();

        paidOrderFor(client, LAMP, 1);

        long started = System.nanoTime();
        assertThat(get(port, "/api/products").statusCode()).isEqualTo(200);
        AdminClient other = customer("busy-buyer");
        assertThat(placeOrderFor(other, NOTEBOOK, 1).get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        await().atMost(Duration.ofSeconds(40)).until(() -> count("event_publication WHERE status = 'FAILED'") == 1);
        assertThat(count("order_email WHERE status = 'FAILED' AND claimed_at IS NULL"))
                .isEqualTo(1);

        mailpit.thaw();
        clock.advance(Duration.ofMinutes(2));
        retryJob.runOnce();

        awaitMessages(1);
        EventFixtures.awaitQuiet(jdbc);
        assertThat(mailpit.messagesTo(email)).hasSize(1);
    }

    @Test
    void aSendFailureLogsNoAddressAndNoCause(CapturedOutput output) {
        doThrow(new MailSendException("refused for <victim@example.test>"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));
        AdminClient client = customerWithEmail("leaky-buyer", email);

        paidOrderFor(client, LAMP, 1);

        await().atMost(Duration.ofSeconds(20)).until(() -> count("event_publication WHERE status = 'FAILED'") == 1);
        assertThat(output.getAll()).contains("ListenerFailedException").doesNotContain("victim@example.test");
    }

    @Test
    void aFreshClaimMakesTheListenerLeaveThePublicationForARetry() {
        AdminClient client = customerWithEmail("claimed-buyer", email);
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        startPayment(client, orderId);
        UUID payment = paymentOf(orderId);
        insertClaim(orderId, "SENDING", clock.instant());

        deliver(newEventId(), "payment.succeeded", payment, LAMP_PRICE, "EUR");

        await().atMost(Duration.ofSeconds(20)).until(() -> count("event_publication WHERE status = 'FAILED'") == 1);
        assertThat(mailpit.messagesTo(email)).isEmpty();

        jdbc.sql("UPDATE order_email SET status = 'SENT'").update();
        clock.advance(Duration.ofMinutes(2));
        retryJob.runOnce();

        EventFixtures.awaitQuiet(jdbc);
        assertThat(mailpit.messagesTo(email)).isEmpty();
    }

    @Test
    void aClaimOlderThanAMinuteIsTakenOverAsACrashedSend() {
        AdminClient client = customerWithEmail("crashed-buyer", email);
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        startPayment(client, orderId);
        UUID payment = paymentOf(orderId);
        insertClaim(orderId, "SENDING", clock.instant().minusSeconds(120));

        deliver(newEventId(), "payment.succeeded", payment, LAMP_PRICE, "EUR");

        awaitMessages(1);
        await().atMost(Duration.ofSeconds(20)).until(() -> "SENT".equals(emailStatus(orderId)));
    }

    private void insertClaim(String orderId, String status, Instant claimedAt) {
        jdbc.sql("""
                        INSERT INTO order_email (order_id, kind, status, claimed_at)
                        VALUES (:id, 'CONFIRMATION', :status, :claimedAt)
                        """)
                .param("id", UUID.fromString(orderId))
                .param("status", status)
                .param("claimedAt", claimedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private JsonNode awaitMessages(int expected) {
        await().atMost(Duration.ofSeconds(40))
                .until(() -> mailpit.messagesTo(email).size() == expected);
        return mailpit.messagesTo(email);
    }

    private HttpResponse<String> mailpitMessage(String id) {
        return mailpit.message(id);
    }

    private String emailStatus(String orderId) {
        return jdbc.sql("SELECT status FROM order_email WHERE order_id = :id")
                .param("id", UUID.fromString(orderId))
                .query(String.class)
                .optional()
                .orElse("NONE");
    }

    private int count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Integer.class)
                .single();
    }
}
