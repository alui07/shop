package dev.shop.api.order;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** Money taken for a payment that the order cannot keep. */
@NamedInterface("payments")
public record RefundRequested(UUID eventId, UUID orderId, UUID paymentId) {}
