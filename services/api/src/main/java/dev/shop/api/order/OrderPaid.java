package dev.shop.api.order;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** An order became paid. Ids only, so no personal data sits in the event registry. */
@NamedInterface("summaries")
public record OrderPaid(UUID eventId, UUID orderId) {}
