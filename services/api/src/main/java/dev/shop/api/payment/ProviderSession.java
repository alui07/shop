package dev.shop.api.payment;

/** The provider's answer to creating a payment. */
public record ProviderSession(String providerPaymentId, String redirectUrl) {}
