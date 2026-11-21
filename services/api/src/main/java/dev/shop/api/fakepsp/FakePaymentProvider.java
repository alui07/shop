package dev.shop.api.fakepsp;

import dev.shop.api.fakepsp.FakePspRepository.FakeRow;
import dev.shop.api.payment.PaymentProvider;
import dev.shop.api.payment.ProviderSession;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** A provider that takes no money. Sessions are idempotent by the shop's payment id (the merchant reference). */
@Component
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "fake")
class FakePaymentProvider implements PaymentProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(FakePaymentProvider.class);
    private static final int TOKEN_BYTES = 32;

    private final FakePspRepository repository;
    private final FakePspProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    FakePaymentProvider(FakePspRepository repository, FakePspProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        LOGGER.warn("fake payment provider: no money is taken");
    }

    @Override
    public ProviderSession createPayment(
            UUID paymentId, long amountMinor, String currency, String returnPath, Instant holdExpiresAt) {
        repository.insertIfAbsent(
                new FakeRow(
                        "pay_" + UUID.randomUUID(),
                        newToken(),
                        paymentId,
                        amountMinor,
                        currency,
                        FakePspStatus.PENDING,
                        returnPath,
                        null,
                        holdExpiresAt.plus(properties.sessionGrace()),
                        null),
                OffsetDateTime.now(clock));
        FakeRow session = repository.findByMerchantReference(paymentId).orElseThrow();
        return new ProviderSession(session.providerPaymentId(), "/fake-psp/" + session.token());
    }

    @Override
    public void refund(String providerPaymentId, long amountMinor, String currency, UUID idempotencyKey) {
        repository.markRefunded(providerPaymentId);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
