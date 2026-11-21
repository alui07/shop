package dev.shop.api.order;

import org.springframework.modulith.NamedInterface;

/** What the order did with a successful payment: took it, or wants the money back. */
@NamedInterface("payments")
public enum PaymentDecision {
    ACCEPTED,
    REFUND
}
