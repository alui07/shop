package dev.shop.api.fakepsp;

import java.util.UUID;

/** The customer finished on the fake page; the webhook sender tells the shop. Ids only. */
record FakePaymentCompleted(UUID eventId, String providerPaymentId) {}
