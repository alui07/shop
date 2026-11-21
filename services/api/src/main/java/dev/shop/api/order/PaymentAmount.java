package dev.shop.api.order;

import org.springframework.modulith.NamedInterface;

/** An amount as it crosses from the order module to the payment module. */
@NamedInterface("payments")
public record PaymentAmount(long amountMinor, String currency) {}
