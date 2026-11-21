package dev.shop.api.order;

import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** What the confirmation email says about a paid order. */
@NamedInterface("summaries")
public record ConfirmationDetails(UUID orderId, String email, List<Line> lines, long totalMinor, String currency) {

    public record Line(String name, int quantity, long unitPriceMinor) {}
}
