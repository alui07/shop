package dev.shop.api.fakepsp;

import dev.shop.api.fakepsp.FakePspRepository.FakeRow;
import dev.shop.api.payment.WebhookSigner;
import dev.shop.api.web.ListenerFailedException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the shop about a completed payment. The body is built from stored fields only, so it is the same on every
 * attempt; the signature is made at each attempt with the time of that attempt. A failure leaves the publication for
 * the retry job.
 */
@Component
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "fake")
class WebhookSender {

    private static final String SIGNATURE_HEADER = "Shop-Signature";

    private final FakePspRepository repository;
    private final WebhookSigner signer;
    private final FakePspProperties properties;
    private final Environment environment;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final RestClient client;

    WebhookSender(
            FakePspRepository repository,
            WebhookSigner signer,
            FakePspProperties properties,
            Environment environment,
            JsonMapper jsonMapper,
            Clock clock) {
        this.repository = repository;
        this.signer = signer;
        this.properties = properties;
        this.environment = environment;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(requestFactory).build();
    }

    @ApplicationModuleListener(id = "fakepsp.webhook-sender", propagation = Propagation.NOT_SUPPORTED)
    void on(FakePaymentCompleted event) throws GeneralSecurityException {
        FakeRow payment =
                repository.findByProviderPaymentId(event.providerPaymentId()).orElseThrow();
        byte[] body = jsonMapper.writeValueAsBytes(PspEvent.of(payment));
        String signature = signer.header(clock.instant(), body);
        try {
            client.post()
                    .uri(webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ListenerFailedException(
                    "Webhook delivery failed with " + e.getClass().getSimpleName());
        }
    }

    private String webhookUrl() {
        return properties.webhookUrl().isBlank()
                ? "http://localhost:" + environment.getProperty("local.server.port") + "/api/payments/webhook"
                : properties.webhookUrl();
    }
}
