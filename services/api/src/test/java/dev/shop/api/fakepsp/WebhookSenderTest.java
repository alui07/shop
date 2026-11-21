package dev.shop.api.fakepsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.shop.api.fakepsp.FakePspRepository.FakeRow;
import dev.shop.api.payment.WebhookSigner;
import dev.shop.api.web.ListenerFailedException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class WebhookSenderTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int answer = 200;
    private final FakePspRepository repository = mock(FakePspRepository.class);
    private final WebhookSigner signer = mock(WebhookSigner.class);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Shop-Signature"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
        server.start();
        when(signer.header(org.mockito.ArgumentMatchers.eq(NOW), org.mockito.ArgumentMatchers.any()))
                .thenReturn("t=" + NOW.getEpochSecond() + ",v1=abc");
        when(repository.findByProviderPaymentId("pay_1"))
                .thenReturn(Optional.of(new FakeRow(
                        "pay_1",
                        "tok",
                        UUID.randomUUID(),
                        100,
                        "EUR",
                        FakePspStatus.SUCCEEDED,
                        "/orders/1",
                        "evt_1",
                        NOW,
                        NOW)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private WebhookSender sender(String webhookUrl, String localPort) {
        return new WebhookSender(
                repository,
                signer,
                new FakePspProperties(Duration.ofMinutes(5), webhookUrl),
                new MockEnvironment().withProperty("local.server.port", localPort),
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aConfiguredUrlIsUsedAsIs() throws Exception {
        sender("http://localhost:" + server.getAddress().getPort() + "/hook", "1")
                .on(new FakePaymentCompleted(UUID.randomUUID(), "pay_1"));

        assertThat(requests).singleElement().asString().startsWith("/hook t=" + NOW.getEpochSecond() + ",v1=");
    }

    @Test
    void aBlankUrlMeansTheServersOwnPort() throws Exception {
        sender("", String.valueOf(server.getAddress().getPort()))
                .on(new FakePaymentCompleted(UUID.randomUUID(), "pay_1"));

        assertThat(requests).singleElement().asString().startsWith("/api/payments/webhook ");
    }

    @Test
    void aRefusalOrAnUnreachableShopFailsTheListenerWithoutACause() {
        answer = 500;
        WebhookSender refused = sender("http://localhost:" + server.getAddress().getPort() + "/hook", "1");
        assertThatThrownBy(() -> refused.on(new FakePaymentCompleted(UUID.randomUUID(), "pay_1")))
                .isInstanceOf(ListenerFailedException.class)
                .hasMessageStartingWith("Webhook delivery failed with")
                .hasNoCause();

        WebhookSender unreachable = sender("http://localhost:1/hook", "1");
        assertThatThrownBy(() -> unreachable.on(new FakePaymentCompleted(UUID.randomUUID(), "pay_1")))
                .isInstanceOf(ListenerFailedException.class)
                .hasNoCause();
    }
}
