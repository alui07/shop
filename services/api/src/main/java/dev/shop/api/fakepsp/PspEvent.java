package dev.shop.api.fakepsp;

import dev.shop.api.fakepsp.FakePspRepository.FakeRow;
import java.util.UUID;

/** The provider event as it goes over the wire; the record's component order fixes the JSON member order. */
record PspEvent(String id, String type, long created, Data data) {

    record Data(String providerPaymentId, UUID merchantReference, long amountMinor, String currency) {}

    static PspEvent of(FakeRow payment) {
        String type = payment.status() == FakePspStatus.SUCCEEDED ? "payment.succeeded" : "payment.failed";
        return new PspEvent(
                payment.eventId(),
                type,
                payment.completedAt().getEpochSecond(),
                new Data(
                        payment.providerPaymentId(),
                        payment.merchantReference(),
                        payment.amountMinor(),
                        payment.currency()));
    }
}
