package dev.shop.api.order;

import java.time.Instant;
import org.springframework.modulith.NamedInterface;

/** What the payment module needs to start paying an order. */
@NamedInterface("payments")
public record PayableOrder(PaymentAmount amount, Instant holdExpiresAt, String returnPath) {}
