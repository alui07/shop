package dev.shop.api.payment;

import java.time.Instant;
import java.util.UUID;

/** The port to a payment provider. Both calls are idempotent, so a retry after a crash is safe. */
public interface PaymentProvider {

    /** Creates the provider's session for {@code paymentId}, or returns the one that already exists for it. */
    ProviderSession createPayment(
            UUID paymentId, long amountMinor, String currency, String returnPath, Instant holdExpiresAt);

    void refund(String providerPaymentId, long amountMinor, String currency, UUID idempotencyKey);
}
